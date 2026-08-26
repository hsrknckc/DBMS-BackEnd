package com.example.dbmslib.mock;

import com.example.dbmslib.protocol.ActionType;
import com.example.dbmslib.protocol.DbRequest;
import com.example.dbmslib.protocol.DbResponse;
import com.example.dbmslib.protocol.JsonCodec;
import com.example.dbmslib.protocol.ResponseStatus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SAHTE ARA KATMAN SUNUCUSU — sadece geliştirme ve test için!
 */
public class MockMiddlewareServer implements AutoCloseable {

    private record UserAccount(String password, Set<ActionType> allowedActions) {
    }

    private static final Map<String, UserAccount> USERS = Map.of(
            "admin", new UserAccount("admin123", Set.of(ActionType.values())),
            "guest", new UserAccount("guest123", Set.of(
                    ActionType.PING, ActionType.READ,
                    ActionType.LIST_DATABASES, ActionType.LIST_COLLECTIONS)),
            "ayse@company.com", new UserAccount("mock-fake-pass-1", Set.of(ActionType.values())),
            "mehmet@company.com", new UserAccount("mock-fake-pass-2", Set.of(
                    ActionType.PING, ActionType.READ, ActionType.WRITE,
                    ActionType.LIST_DATABASES, ActionType.LIST_COLLECTIONS))
    );

    private final Map<String, Map<String, List<Map<String, Object>>>> store = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(100);

    private final ServerSocket serverSocket;
    private final Thread acceptThread;

    public MockMiddlewareServer(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.acceptThread = new Thread(this::acceptLoop, "mock-middleware-accept");
        this.acceptThread.setDaemon(true);
    }

    public void start() {
        acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Thread handler = new Thread(() -> handleClient(client), "mock-middleware-client");
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                System.err.println("[mock] Bağlantı kabul hatası: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = in.readLine()) != null) {
                DbResponse response;
                String requestId = null;
                try {
                    DbRequest request = JsonCodec.fromJson(line, DbRequest.class);
                    requestId = request.getRequestId();
                    response = handleRequest(request);
                } catch (Exception e) {
                    response = new DbResponse(requestId, ResponseStatus.ERROR,
                            "İstek işlenemedi: " + e.getMessage(), null);
                }
                out.write(JsonCodec.toJson(response));
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            // baglanti koptu
        }
    }

    private DbResponse handleRequest(DbRequest request) {
        String requestId = request.getRequestId();

        if (request.getAction() == null) {
            return error(requestId, "'action' alanı zorunludur");
        }

        UserAccount user = USERS.get(request.getUsername());
        if (user == null || !user.password().equals(request.getPassword())) {
            return new DbResponse(requestId, ResponseStatus.UNAUTHORIZED,
                    "Kullanıcı adı veya şifre hatalı", null);
        }
        if (!user.allowedActions().contains(request.getAction())) {
            return new DbResponse(requestId, ResponseStatus.UNAUTHORIZED,
                    request.getUsername() + " kullanıcısının " + request.getAction() + " yetkisi yok", null);
        }

        return switch (request.getAction()) {
            case PING -> ok(requestId, "MongoDB (sahte) aktif", null);
            case WRITE -> doWrite(request);
            case READ -> doRead(request);
            case UPDATE -> doUpdate(request);
            case DELETE -> doDelete(request);
            case LIST_DATABASES -> ok(requestId, store.size() + " veritabanı",
                    new ArrayList<>(store.keySet()));
            case LIST_COLLECTIONS -> doListCollections(request);
        };
    }

    private DbResponse doWrite(DbRequest request) {
        if (request.getDatabase() == null || request.getCollection() == null || request.getDocument() == null) {
            return error(request.getRequestId(), "WRITE için database, collection ve document zorunludur");
        }
        Map<String, Object> doc = request.getDocument();
        if (doc.isEmpty()) {
            return error(request.getRequestId(), "document field must be a non-empty object");
        }

        // Sistem Alanlari Guvenligi
        if (doc.containsKey("id") || doc.containsKey("_id")) {
            return error(request.getRequestId(), "Field 'id' is generated by server and cannot be provided");
        }
        if (doc.containsKey("createdAt")) {
            return error(request.getRequestId(), "Field 'createdAt' is set by server and cannot be provided");
        }
        if (doc.containsKey("updatedAt")) {
            return error(request.getRequestId(), "Field 'updatedAt' is set by server and cannot be provided");
        }
        if (doc.containsKey("isDeleted")) {
            return error(request.getRequestId(), "Field 'isDeleted' is managed by server and cannot be provided");
        }

        // Recursive $ Kontrolu
        String injectionErr = checkInjection(doc);
        if (injectionErr != null) {
            return error(request.getRequestId(), injectionErr);
        }

        String typeError = validateTypes(request);
        if (typeError != null) {
            return error(request.getRequestId(), typeError);
        }

        // Sunucu Alanlarini Ekle
        Map<String, Object> record = new LinkedHashMap<>(doc);
        String id = "rec-" + idCounter.incrementAndGet();
        String now = Instant.now().toString();
        record.put("id", id);
        record.put("createdAt", now);
        record.put("updatedAt", now);

        collectionOf(request).add(record);
        return ok(request.getRequestId(), "1 record inserted", List.of(record));
    }

    private String validateTypes(DbRequest request) {
        for (Map.Entry<String, Object> field : request.getDocument().entrySet()) {
            if (field.getKey().startsWith("sayi_") && !(field.getValue() instanceof Number)) {
                return "Invalid data format: Field '" + field.getKey()
                        + "' must be of type int but got "
                        + (field.getValue() == null ? "null" : field.getValue().getClass().getSimpleName());
            }
        }
        return null;
    }

    private DbResponse doRead(DbRequest request) {
        if (request.getDatabase() == null || request.getCollection() == null) {
            return error(request.getRequestId(), "READ için database ve collection zorunludur");
        }
        String injectionErr = checkInjection(request.getFilter());
        if (injectionErr != null) {
            return error(request.getRequestId(), injectionErr);
        }

        List<Object> matches = new ArrayList<>();
        for (Map<String, Object> record : collectionOf(request)) {
            if (matchesFilter(record, request.getFilter())) {
                matches.add(new LinkedHashMap<>(record));
            }
        }
        return ok(request.getRequestId(), matches.size() + " record(s) found", matches);
    }

    private DbResponse doUpdate(DbRequest request) {
        if (request.getDatabase() == null || request.getCollection() == null || request.getDocument() == null) {
            return error(request.getRequestId(), "UPDATE için database, collection ve document zorunludur");
        }
        Map<String, Object> doc = request.getDocument();
        if (doc.isEmpty()) {
            return error(request.getRequestId(), "document field must be a non-empty object");
        }

        // Sistem Alanlari Guvenligi
        if (doc.containsKey("id") || doc.containsKey("_id")) {
            return error(request.getRequestId(), "Field 'id' cannot be updated");
        }
        if (doc.containsKey("createdAt")) {
            return error(request.getRequestId(), "Field 'createdAt' cannot be updated");
        }
        if (doc.containsKey("updatedAt")) {
            return error(request.getRequestId(), "Field 'updatedAt' is managed by server and cannot be updated");
        }

        // Bos Filtre Kalkani
        Map<String, Object> filter = request.getFilter();
        if (filter == null || filter.isEmpty()) {
            return error(request.getRequestId(), "Filter is required for UPDATE. Mass update with empty filter is not allowed.");
        }

        String injectionErr = checkInjection(filter);
        if (injectionErr != null) {
            return error(request.getRequestId(), injectionErr);
        }

        int updated = 0;
        String now = Instant.now().toString();
        List<Object> updatedList = new ArrayList<>();

        for (Map<String, Object> record : collectionOf(request)) {
            if (matchesFilter(record, filter)) {
                record.putAll(doc);
                record.put("updatedAt", now);
                updatedList.add(new LinkedHashMap<>(record));
                updated++;
            }
        }
        return ok(request.getRequestId(), updated + " record(s) updated", updatedList);
    }

    private DbResponse doDelete(DbRequest request) {
        if (request.getDatabase() == null || request.getCollection() == null) {
            return error(request.getRequestId(), "DELETE için database ve collection zorunludur");
        }

        // Bos Filtre Kalkani
        Map<String, Object> filter = request.getFilter();
        if (filter == null || filter.isEmpty()) {
            return error(request.getRequestId(), "Filter is required for DELETE. Mass deletion with empty filter is not allowed.");
        }

        String injectionErr = checkInjection(filter);
        if (injectionErr != null) {
            return error(request.getRequestId(), injectionErr);
        }

        List<Map<String, Object>> collection = collectionOf(request);
        int before = collection.size();
        collection.removeIf(record -> matchesFilter(record, filter));
        int deleted = before - collection.size();
        return ok(request.getRequestId(), deleted + " record(s) deleted", null);
    }

    private DbResponse doListCollections(DbRequest request) {
        if (request.getDatabase() == null) {
            return error(request.getRequestId(), "LIST_COLLECTIONS için database zorunludur");
        }
        Map<String, List<Map<String, Object>>> db =
                store.getOrDefault(request.getDatabase(), Map.of());
        return ok(request.getRequestId(), db.size() + " koleksiyon", new ArrayList<>(db.keySet()));
    }

    private List<Map<String, Object>> collectionOf(DbRequest request) {
        return store
                .computeIfAbsent(request.getDatabase(), name -> new ConcurrentHashMap<>())
                .computeIfAbsent(request.getCollection(), name -> new ArrayList<>());
    }

    private String checkInjection(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("$")) {
                    return "Dangerous query operator: " + key;
                }
                String nestedErr = checkInjection(entry.getValue());
                if (nestedErr != null) return nestedErr;
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                String nestedErr = checkInjection(item);
                if (nestedErr != null) return nestedErr;
            }
        }
        return null;
    }

    private boolean matchesFilter(Map<String, Object> record, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (record == null) {
            return false;
        }

        // _id toleransi: hem id hem _id varsa ve eslesiyorsa sadeleştir
        Map<String, Object> cleanFilter = new HashMap<>(filter);
        if (cleanFilter.containsKey("_id") && cleanFilter.containsKey("id")) {
            if (Objects.equals(cleanFilter.get("_id"), cleanFilter.get("id"))) {
                cleanFilter.remove("_id");
            }
        }

        for (Map.Entry<String, Object> condition : cleanFilter.entrySet()) {
            String key = condition.getKey();
            if ("_id".equals(key) && !record.containsKey("_id") && record.containsKey("id")) {
                key = "id";
            }

            Object actual = record.get(key);
            Object expected = condition.getValue();
            if (expected instanceof Map<?, ?> opMap) {
                if (!matchesOperators(actual, castOpMap(opMap))) {
                    return false;
                }
            } else if (!looselyEquals(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castOpMap(Map<?, ?> opMap) {
        return (Map<String, Object>) opMap;
    }

    private boolean matchesOperators(Object actual, Map<String, Object> ops) {
        for (Map.Entry<String, Object> opEntry : ops.entrySet()) {
            String op = String.valueOf(opEntry.getKey()).toLowerCase().trim();
            Object targetVal = opEntry.getValue();

            switch (op) {
                case ">", "gt" -> {
                    if (compare(actual, targetVal) <= 0) return false;
                }
                case ">=", "gte" -> {
                    if (compare(actual, targetVal) < 0) return false;
                }
                case "<", "lt" -> {
                    if (compare(actual, targetVal) >= 0) return false;
                }
                case "<=", "lte" -> {
                    if (compare(actual, targetVal) > 0) return false;
                }
                case "!=", "ne" -> {
                    if (looselyEquals(actual, targetVal)) return false;
                }
                case "like", "contains" -> {
                    if (actual == null || targetVal == null) return false;
                    String actualStr = String.valueOf(actual).toLowerCase();
                    String targetStr = String.valueOf(targetVal).toLowerCase();
                    if (!actualStr.contains(targetStr)) return false;
                }
                default -> throw new IllegalArgumentException("Unsupported filter operator: " + op);
            }
        }
        return true;
    }

    private boolean looselyEquals(Object actual, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            return compare(actual, expected) == 0;
        }
        return Objects.equals(String.valueOf(actual), String.valueOf(expected));
    }

    private int compare(Object actual, Object expected) {
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            return Double.compare(actualNumber.doubleValue(), expectedNumber.doubleValue());
        }
        if (actual instanceof Comparable<?> && expected != null
                && actual.getClass().isAssignableFrom(expected.getClass())) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) actual;
            return comparable.compareTo(expected);
        }
        return String.valueOf(actual).compareTo(String.valueOf(expected));
    }

    private DbResponse ok(String requestId, String message, List<Object> data) {
        return new DbResponse(requestId, ResponseStatus.OK, message, data);
    }

    private DbResponse error(String requestId, String message) {
        return new DbResponse(requestId, ResponseStatus.ERROR, message, null);
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5150;
        MockMiddlewareServer server = new MockMiddlewareServer(port);
        server.start();
        System.out.println("[mock] Sahte ara katman " + server.getPort() + " portunda dinliyor.");
        System.out.println("[mock] Kullanıcılar: admin/admin123 (tam yetki), guest/guest123 (sadece okuma)");
        System.out.println("[mock] Durdurmak için Ctrl+C.");
        Thread.currentThread().join();
    }
}