package com.example.dbmslib;

import com.example.dbmslib.client.DbmsClient;
import com.example.dbmslib.exception.DbmsException;
import com.example.dbmslib.mock.MockMiddlewareServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kütüphanenin uçtan uca testleri: gerçek bir TCP bağlantısı üzerinden
 * sahte ara katman sunucusuyla konuşulur. Gerçek ara katman hazır
 * olduğunda aynı senaryoların onunla da çalışması beklenir.
 */
class DbmsClientTest {

    private static MockMiddlewareServer server;
    private static DbmsClient admin;  // tam yetkili kullanıcı
    private static DbmsClient guest;  // sadece okuma yetkili kullanıcı

    @BeforeAll
    static void startServer() throws Exception {
        server = new MockMiddlewareServer(0); // 0 -> boş port otomatik seçilir
        server.start();
        admin = new DbmsClient("localhost", server.getPort(), "admin", "admin123");
        guest = new DbmsClient("localhost", server.getPort(), "guest", "guest123");
    }

    @AfterAll
    static void stopServer() throws Exception {
        admin.close();
        guest.close();
        server.close();
    }

    @Test
    void pingSunucuAyaktaykenTrueDoner() { // Ister_0018
        assertTrue(admin.ping());
    }

    @Test
    void pingSunucuKapaliykenFalseDoner() throws Exception {
        // Kimsenin dinlemediği bir port bul: aç-kapa yap, numarasını kullan
        int closedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            closedPort = temp.getLocalPort();
        }
        try (DbmsClient unreachable = new DbmsClient("localhost", closedPort, "admin", "admin123")) {
            assertFalse(unreachable.ping());
        }
    }

    @Test
    void yazilanKayitGeriOkunur() { // Ister_0016 + Ister_0017
        admin.write("test_okul", "ogrenciler", Map.of("ad", "Ali", "sinif", 3));

        List<Map<String, Object>> records = admin.read("test_okul", "ogrenciler", null);

        assertEquals(1, records.size());
        assertEquals("Ali", records.get(0).get("ad"));
        assertEquals(3, records.get(0).get("sinif"));
    }

    @Test
    void filtreSadeceUyanKayitlariDondurur() {
        admin.write("test_filtre", "ogrenciler", Map.of("ad", "Ayşe", "sinif", 3));
        admin.write("test_filtre", "ogrenciler", Map.of("ad", "Mehmet", "sinif", 4));

        List<Map<String, Object>> records = admin.read("test_filtre", "ogrenciler", Map.of("sinif", 4));

        assertEquals(1, records.size());
        assertEquals("Mehmet", records.get(0).get("ad"));
    }

    @Test
    void updateFiltreyeUyanKaydiGunceller() {
        admin.write("test_update", "ogrenciler", Map.of("ad", "Zeynep", "sinif", 2));

        admin.update("test_update", "ogrenciler", Map.of("ad", "Zeynep"), Map.of("sinif", 3));

        List<Map<String, Object>> records = admin.read("test_update", "ogrenciler", null);
        assertEquals(3, records.get(0).get("sinif"));
    }

    @Test
    void deleteFiltreyeUyanKaydiSiler() {
        admin.write("test_delete", "ogrenciler", Map.of("ad", "Can"));
        admin.write("test_delete", "ogrenciler", Map.of("ad", "Ece"));

        admin.delete("test_delete", "ogrenciler", Map.of("ad", "Can"));

        List<Map<String, Object>> records = admin.read("test_delete", "ogrenciler", null);
        assertEquals(1, records.size());
        assertEquals("Ece", records.get(0).get("ad"));
    }

    @Test
    void listDatabasesYazilanVeritabaniniIcerir() {
        admin.write("test_liste", "herhangi", Map.of("x", 1));

        assertTrue(admin.listDatabases().contains("test_liste"));
        assertTrue(admin.listCollections("test_liste").contains("herhangi"));
    }

    @Test
    void okumaYetkiliKullaniciYazamaz() { // Ister_0015'in istemci tarafı
        DbmsException e = assertThrows(DbmsException.class,
                () -> guest.write("test_yetki", "veri", Map.of("x", 1)));
        assertTrue(e.isUnauthorized());
    }

    @Test
    void yanlisSifreyleIstekReddedilir() {
        try (DbmsClient wrongPassword =
                     new DbmsClient("localhost", server.getPort(), "admin", "yanlis")) {
            DbmsException e = assertThrows(DbmsException.class,
                    () -> wrongPassword.read("test_okul", "ogrenciler", null));
            assertTrue(e.isUnauthorized());
        }
    }
}
