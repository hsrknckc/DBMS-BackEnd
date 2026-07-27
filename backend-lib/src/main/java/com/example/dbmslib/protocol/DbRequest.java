package com.example.dbmslib.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * Ara katmana TCP üzerinden gönderilen tek bir isteğin JSON karşılığı.
 *
 * Örnek (tek satır olarak gider):
 * {"requestId":"...","action":"READ","username":"ozan","password":"...",
 *  "database":"okul","collection":"ogrenciler","filter":{"sinif":3}}
 *
 * username/password her istekte taşınır; ara katman her isteği kullanıcı
 * yetkinliğine göre cevaplar (Ister_0015).
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // null alanlar JSON'a yazılmaz, mesaj kısa kalır
public class DbRequest {

    private String requestId;
    private ActionType action;
    private String username;
    private String password;
    private String database;
    private String collection;
    /** Hangi kayıtlar? (READ/UPDATE/DELETE için süzgeç, örn. {"sinif":3}) */
    private Map<String, Object> filter;
    /** Yazılacak yeni kayıt (WRITE) veya güncellenecek alanlar (UPDATE). */
    private Map<String, Object> document;

    public DbRequest() {
        // Jackson'ın JSON'dan nesne üretebilmesi için boş kurucu gerekli
    }

    public DbRequest(ActionType action, String username, String password) {
        this.requestId = UUID.randomUUID().toString();
        this.action = action;
        this.username = username;
        this.password = password;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }

    public Map<String, Object> getFilter() { return filter; }
    public void setFilter(Map<String, Object> filter) { this.filter = filter; }

    public Map<String, Object> getDocument() { return document; }
    public void setDocument(Map<String, Object> document) { this.document = document; }
}
