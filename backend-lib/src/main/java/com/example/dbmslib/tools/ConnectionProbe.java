package com.example.dbmslib.tools;

import com.example.dbmslib.client.DbmsClient;
import com.example.dbmslib.exception.DbmsException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BAGLANTI TANILAMA ARACI.
 *
 * Ara katman sunucusuna baglanip temel islemleri sirayla dener ve sonucu
 * ekrana basar. "Baglanamiyorum" durumunda sorunun hangi asamada oldugunu
 * (ag mi, kimlik mi, yetki mi) gorebilmek icin kullanilir.
 *
 * Calistirma:
 *   ./gradlew :backend-lib:runProbe
 *   ./gradlew :backend-lib:runProbe --args="HOST PORT KULLANICI SIFRE"
 */
public class ConnectionProbe {

    // Varsayilanlar: ekibin AWS EC2 uzerindeki ortak ara katman sunucusu
    private static final String DEFAULT_HOST = "54.154.220.190";
    private static final int DEFAULT_PORT = 5150;

    public static void main(String[] args) {
        
        String host = args.length > 0
        ? args[0]
        : env("MIDDLEWARE_HOST", DEFAULT_HOST);

        int port = args.length > 1
        ? Integer.parseInt(args[1])
        : Integer.parseInt(env("MIDDLEWARE_PORT", String.valueOf(DEFAULT_PORT)));
        // GUVENLIK: gercek sifre icin varsayilan deger YOK - bu depo herkese acik.
        // Once "source set-credentials.sh" calistir ya da --args ile ver.
        String user = args.length > 2 ? args[2] : env("MIDDLEWARE_USER", null);
        String pass = args.length > 3 ? args[3] : env("MIDDLEWARE_PASSWORD", null);
        if (user == null || pass == null) {
            System.out.println("HATA: kullanici adi/sifre bulunamadi.");
            System.out.println("Once calistir : source set-credentials.sh");
            System.out.println("ya da         : ./gradlew :backend-lib:runProbe --args=\"HOST PORT KULLANICI SIFRE\"");
            return;
        }

        System.out.println("Ara katman     : " + host + ":" + port);
        System.out.println("Kullanici      : " + user);
        System.out.println("-".repeat(60));

        try (DbmsClient client = new DbmsClient(host, port, user, pass)) {

            // 1) Sunucu ayakta mi? (Ister_0018)
            DbmsClient.HealthStatus health = client.checkHealth();

            System.out.println("1) PING              : "
                + (health.alive() ? "OK - sunucu aktif" : "BASARISIZ"));

            System.out.println("   Detay             : " + health.detail());

            boolean alive = health.alive(); 
            if (!alive) {
                System.out.println();
                System.out.println("Sunucuya ulasilamadi veya kimlik dogrulanamadi. Kontrol listesi:");
                System.out.println("  - Adres/port dogru mu?");
                System.out.println("  - Kullanici adi TAM E-POSTA mi? (ornek@company.com)");
                System.out.println("  - Sifre dogru mu?");
                System.out.println("  - Sunucu calisiyor mu? (ekipten sorun)");
                return;
            }

            // 2) Veritabanlarini listele
            List<String> databases = client.listDatabases();
            System.out.println("2) LIST_DATABASES    : OK - " + databases);

            // 3) Ic ice veri yaz (Ister_0017) - liderin ornek JSON yapisina benzer
            String db = "probe_test";
            String collection = "kayitlar";
            Map<String, Object> document = nestedSampleDocument();
            client.write(db, collection, document);
            System.out.println("3) WRITE (ic ice)    : OK - kayit eklendi");

            // 4) Geri oku (Ister_0016) ve ic ice yapinin korundugunu dogrula
            List<Map<String, Object>> records = client.read(db, collection, null);
            System.out.println("4) READ              : OK - " + records.size() + " kayit");

            boolean nestedOk = verifyNested(records);
            System.out.println("5) Ic ice veri       : " + (nestedOk
                    ? "OK - dizi ve ic ice nesneler korunmus"
                    : "UYARI - yapi beklendigi gibi donmedi"));

            System.out.println("-".repeat(60));
            System.out.println(nestedOk
                    ? "SONUC: Zincirin tamami calisiyor."
                    : "SONUC: Baglanti calisiyor ama veri yapisini kontrol edin.");

        } catch (DbmsException e) {
            System.out.println();
            System.out.println("HATA: " + e.getMessage());
            if (e.isUnauthorized()) {
                System.out.println("Bu bir YETKI hatasi. Kullanicinin bu islem icin yetkisi yok");
                System.out.println("ya da kullanici adi tam e-posta olarak yazilmamis.");
            } else {
                System.out.println("Baglanti kurulamadi veya sunucu hata dondu.");
            }
        }
    }

    /** Liderin ornek JSON dosyalarindaki gibi ic ice nesne + dizi iceren kayit. */
    private static Map<String, Object> nestedSampleDocument() {
        Map<String, Object> personal = new LinkedHashMap<>();
        personal.put("name", "Ali");
        personal.put("surname", "Yilmaz");
        personal.put("age", 22);

        Map<String, Object> computer = new LinkedHashMap<>();
        computer.put("cpu", "M1");
        computer.put("brand", "Apple");

        Map<String, Object> technology = new LinkedHashMap<>();
        technology.put("computer", List.of(computer));
        technology.put("ram", 16);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("personal", personal);
        document.put("preferences", List.of(
                Map.of("color", "mavi"),
                Map.of("book", "roman")));
        document.put("technology", technology);
        return document;
    }

    /** Okunan kayitlarda ic ice nesne ve dizinin bozulmadan geldigini kontrol eder. */
    @SuppressWarnings("unchecked")
    private static boolean verifyNested(List<Map<String, Object>> records) {
        for (Map<String, Object> record : records) {
            Object personal = record.get("personal");
            Object preferences = record.get("preferences");
            if (personal instanceof Map<?, ?> p
                    && "Ali".equals(p.get("name"))
                    && preferences instanceof List<?> list
                    && !list.isEmpty()
                    && list.get(0) instanceof Map) {
                return true;
            }
        }
        return false;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
