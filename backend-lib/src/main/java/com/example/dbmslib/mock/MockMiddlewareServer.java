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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAHTE ARA KATMAN SUNUCUSU — sadece geliştirme ve test için!
 *
 * Ara katmancı ekip arkadaşının gerçek sunucusu hazır olana kadar,
 * PROTOKOL.md'deki sözleşmeyi birebir konuşan bu mini sunucuyla
 * kütüphaneyi geliştirip test edebilirsin. Veriyi MongoDB yerine
 * bellekte (HashMap) tutar; sunucu kapanınca veri uçar.
 *
 * Gerçek ara katman hazır olduğunda tek yapman gereken host/port
 * ayarını onun adresine çevirmek — kütüphane kodu değişmez.
 *
 * Elle çalıştırmak için: ./gradlew :backend-lib:runMockServer
 */
public class MockMiddlewareServer implements AutoCloseable {

    /** Sahte kullanıcılar: kullanıcı adı -> [şifre, yetkili olduğu işlemler] */
    private record UserAccount(String password, Set<ActionType> allowedActions) {
    }

    private static final Map<String, UserAccount> USERS = Map.of(
            // admin her şeyi yapabilir
            "admin", new UserAccount("admin123", Set.of(ActionType.values())),
            // guest sadece okuyabilir — yetki reddi (UNAUTHORIZED) senaryosunu test etmek için
            "guest", new UserAccount("guest123", Set.of(
                    ActionType.PING, ActionType.READ,
                    ActionType.LIST_DATABASES, ActionType.LIST_COLLECTIONS)),

            // Gerçek ara katman sunucusundaki kullanıcı ADLARININ aynısı (yalnızca
            // e-posta kısmı), ama BİLEREK FARKLI, uydurma bir şifreyle. Bu depo
            // herkese açık; gerçek EC2 şifresi hiçbir zaman burada yazmaz.
            // Yerel testte bu kullanıcıları kullanmak istersen set-credentials.sh
            // dosyandaki (git'e gitmeyen) gerçek şifreyi DEĞİL, aşağıdaki sahte
            // şifreyi gir.
            "ayse@company.com", new UserAccount("mock-fake-pass-1", Set.of(ActionType.values())),
            // mehmet sınırlı yetkili: okuyabilir ve yazabilir, ama güncelleyemez/silemez
            "mehmet@company.com", new UserAccount("mock-fake-pass-2", Set.of(
                    ActionType.PING, ActionType.READ, ActionType.WRITE,
                    ActionType.LIST_DATABASES, ActionType.LIST_COLLECTIONS))
    );

    /** Bellekteki "MongoDB": veritabanı adı -> (koleksiyon adı -> kayıt listesi) */
    private final Map<String, Map<String, List<Map<String, Object>>>> store = new ConcurrentHashMap<>();

    private final ServerSocket serverSocket;
    private final Thread acceptThread;

    /** @param port 0 verilirse boş bir port otomatik seçilir (testler için ideal) */
    public MockMiddlewareServer(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.acceptThread = new Thread(this::acceptLoop, "mock-middleware-accept");
        this.acceptThread.setDaemon(true);
    }

    public void start() {
        acceptThread.start();
    }

    /** Sunucunun gerçekte dinlediği port (kurucuya 0 verildiyse buradan öğrenilir). */
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
                return; // close() çağrıldı, döngüden çık
            } catch (IOException e) {
                System.err.println("[mock] Bağlantı kabul hatası: " + e.getMessage());
            }
        }
    }

    /** Bir istemcinin gönderdiği satırları tek tek okuyup her birine cevap yazar. */
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
            // istemci koptu; sunucu çalışmaya devam eder
        }
    }

    private DbResponse handleRequest(DbRequest request) {
        String requestId = request.getRequestId();

        if (request.getAction() == null) {
            return error(requestId, "'action' alanı zorunludur");
        }

        // Kimlik + yetki kontrolü (Ister_0015'in sahtesi)
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
        String typeError = validateTypes(request);
        if (typeError != null) {
            return error(request.getRequestId(), typeError);
        }
        collectionOf(request).add(new HashMap<>(request.getDocument()));
        return ok(request.getRequestId(), "1 kayıt eklendi", null);
    }

    /**
     * Gerçek ara katmandaki tip doğrulamasının (Ister_0014) sadeleştirilmiş
     * taklidi: "sayi_" ile başlayan alanlar sayı olmak zorundadır.
     *
     * Amaç gerçek şema motorunu kopyalamak değil; kütüphanenin ERROR cevabını
     * doğru taşıdığını sahte sunucuyla da test edebilmek.
     *
     * @return hata mesajı, sorun yoksa null
     */
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
        List<Object> matches = new ArrayList<>();
        for (Map<String, Object> record : collectionOf(request)) {
            if (matchesFilter(record, request.getFilter())) {
                matches.add(new HashMap<>(record));
            }
        }
        return ok(request.getRequestId(), matches.size() + " kayıt bulundu", matches);
    }

    private DbResponse doUpdate(DbRequest request) {
        if (request.getDatabase() == null || request.getCollection() == null || request.getDocument() == null) {
            return error(request.getRequestId(), "UPDATE için database, collection ve document zorunludur");
        }
        int updated = 0;
        for (Map<String, Object> record : collectionOf(request)) {
            if (matchesFilter(record, request.getFilter())) {
                record.putAll(request.getDocument());
                updated++;
            }
        }
        return ok(request.getRequestId(), updated + " kayıt güncellendi", null);
    }

    private DbResponse doDelete(DbRequest request) {
        if (request.getDatabase() == null || request.getCollection() == null) {
            return error(request.getRequestId(), "DELETE için database ve collection zorunludur");
        }
        List<Map<String, Object>> collection = collectionOf(request);
        int before = collection.size();
        collection.removeIf(record -> matchesFilter(record, request.getFilter()));
        return ok(request.getRequestId(), (before - collection.size()) + " kayıt silindi", null);
    }

    private DbResponse doListCollections(DbRequest request) {
        if (request.getDatabase() == null) {
            return error(request.getRequestId(), "LIST_COLLECTIONS için database zorunludur");
        }
        Map<String, List<Map<String, Object>>> db =
                store.getOrDefault(request.getDatabase(), Map.of());
        return ok(request.getRequestId(), db.size() + " koleksiyon", new ArrayList<>(db.keySet()));
    }

    /** İstenen veritabanı/koleksiyonu bulur, yoksa oluşturur (MongoDB davranışı). */
    private List<Map<String, Object>> collectionOf(DbRequest request) {
        return store
                .computeIfAbsent(request.getDatabase(), name -> new ConcurrentHashMap<>())
                .computeIfAbsent(request.getCollection(), name -> new ArrayList<>());
    }

    /**
     * Kayıt filtreye uyuyor mu? Filtredeki her alan kayıtta aynı değerle
     * bulunmalı. Sayı/metin karşılaştırması esnek yapılır ("3" == 3) çünkü
     * HTTP query parametreleri her zaman metin olarak gelir.
     */
    private boolean matchesFilter(Map<String, Object> record, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true; // filtre yoksa tüm kayıtlar uyar
        }
        for (Map.Entry<String, Object> condition : filter.entrySet()) {
            Object actual = record.get(condition.getKey());
            if (!String.valueOf(actual).equals(String.valueOf(condition.getValue()))) {
                return false;
            }
        }
        return true;
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

    /** Elle çalıştırma girişi: portu argüman olarak alabilir, almazsa 5150. */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5150;
        MockMiddlewareServer server = new MockMiddlewareServer(port);
        server.start();
        System.out.println("[mock] Sahte ara katman " + server.getPort() + " portunda dinliyor.");
        System.out.println("[mock] Kullanıcılar: admin/admin123 (tam yetki), guest/guest123 (sadece okuma)");
        System.out.println("[mock] Durdurmak için Ctrl+C.");
        Thread.currentThread().join(); // sonsuza kadar bekle
    }
}
