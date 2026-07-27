package com.example.dbmslib.connection;

import com.example.dbmslib.exception.DbmsException;
import com.example.dbmslib.protocol.DbRequest;
import com.example.dbmslib.protocol.DbResponse;
import com.example.dbmslib.protocol.JsonCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Ara katmanla ham TCP soketi üzerinden konuşan katman (Ister_0019).
 *
 * Protokol: her mesaj tek satır JSON'dur ve '\n' ile biter.
 * Bir istek satırı gönderilir, karşılığında bir cevap satırı okunur.
 *
 * Bağlantı tembelce (ilk istekte) açılır ve açık tutulur; koparsa bir
 * sonraki istekte otomatik yeniden bağlanma denenir.
 */
public class MiddlewareConnection implements AutoCloseable {

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    public MiddlewareConnection(String host, int port) {
        this(host, port, 3000, 10000);
    }

    public MiddlewareConnection(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Bir isteği gönderip cevabını döner.
     * synchronized: aynı anda tek istek gider; cevap satırlarının
     * birbirine karışması bu sayede imkansızdır.
     */
    public synchronized DbResponse send(DbRequest request) {
        try {
            ensureConnected();
            return exchange(request);
        } catch (IOException firstFailure) {
            // Bağlantı bayatlamış olabilir (örn. sunucu yeniden başladı):
            // bir kez temiz bağlantıyla tekrar dene, sonra pes et.
            closeQuietly();
            try {
                ensureConnected();
                return exchange(request);
            } catch (IOException secondFailure) {
                closeQuietly();
                throw new DbmsException(
                        "Ara katmana ulaşılamadı (" + host + ":" + port + "): " + secondFailure.getMessage(),
                        secondFailure);
            }
        }
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs); // cevap gelmezse sonsuza kadar bekleme
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private DbResponse exchange(DbRequest request) throws IOException {
        writer.write(JsonCodec.toJson(request));
        writer.write('\n');
        writer.flush();

        String line = reader.readLine();
        if (line == null) {
            // Sunucu cevap yazmadan bağlantıyı kapattı
            throw new IOException("Ara katman bağlantıyı kapattı");
        }

        DbResponse response = JsonCodec.fromJson(line, DbResponse.class);
        if (response.getRequestId() != null && request.getRequestId() != null
                && !request.getRequestId().equals(response.getRequestId())) {
            throw new DbmsException("Cevap başka bir isteğe ait: beklenen "
                    + request.getRequestId() + ", gelen " + response.getRequestId());
        }
        return response;
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // kapatma hatası yapılacak bir şey bırakmaz
        } finally {
            socket = null;
            reader = null;
            writer = null;
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }
}
