# Arka Yüz (Backend) — VTYS MongoDB Projesi

Bu depo, VTYS MONGODB projesinin **Arka Yüz** parçasıdır: MongoDB'ye
**ara katman üzerinden** okuma/yazma yapan, sunucu aktifliğini gözetleyen
bir **Java kütüphanesi** ve onu kullanan örnek bir REST API uygulaması.

## Karşılanan isterler

| İster | Ne diyor | Nerede karşılanıyor |
|---|---|---|
| Ister_0016 | Ara katman aracılığıyla okuma | `DbmsClient.read()` |
| Ister_0017 | Ara katman aracılığıyla yazma | `DbmsClient.write()` |
| Ister_0018 | Sunucu aktifliği kontrolü | `DbmsClient.ping()` + `HealthMonitor` |
| Ister_0019 | TCP/IP ile haberleşme | `MiddlewareConnection` (ham `java.net.Socket`) |

## Modüller

```
backend-lib/   Arka Yüz kütüphanesi (saf Java, Spring YOK — herhangi bir uygulamaya eklenebilir JAR)
demo-api/      Kütüphaneyi kullanan örnek Spring Boot REST uygulaması
PROTOKOL.md    Ara katmanla konuşulan TCP mesaj sözleşmesi (ekip arkadaşlarına verilecek)
```

### backend-lib içindeki paketler

| Paket | Görev |
|---|---|
| `protocol` | TCP'de taşınan JSON mesajların Java karşılıkları (`DbRequest`, `DbResponse`, `ActionType`) |
| `connection` | Ham TCP soket yönetimi: bağlan, satır gönder, satır oku (`MiddlewareConnection`) |
| `client` | Kütüphanenin dışa açılan yüzü (`DbmsClient`) — kullanıcı sadece bunu görür |
| `health` | Arka planda periyodik ping atan gözetleyici (`HealthMonitor`) |
| `exception` | Tek tip hata sınıfı (`DbmsException`) |
| `mock` | Test/geliştirme için sahte ara katman sunucusu (`MockMiddlewareServer`) |

## Nasıl çalıştırılır?

### 1. Testler

```bash
./gradlew test
```

Testler, sahte ara katman sunucusunu ayağa kaldırıp gerçek TCP bağlantısı
üzerinden ping / yazma / okuma / güncelleme / silme / yetki senaryolarını dener.

### 2. Elle deneme (gerçek ara katman henüz yokken)

İki ayrı terminalde:

```bash
# Terminal 1 — sahte ara katman (5150 portunda dinler)
./gradlew :backend-lib:runMockServer

# Terminal 2 — örnek REST API (8080 portunda dinler)
./gradlew :demo-api:bootRun
```

Sonra üçüncü bir terminalden:

```bash
curl http://localhost:8080/api/health
curl -X POST http://localhost:8080/api/okul/ogrenciler -H "Content-Type: application/json" -d '{"ad":"Ali","sinif":3}'
curl "http://localhost:8080/api/okul/ogrenciler"
curl http://localhost:8080/api/databases
```

Sahte sunucudaki kullanıcılar: `admin/admin123` (tam yetki), `guest/guest123` (sadece okuma).

### 3. Gerçek ara katmana bağlanma

Ara katmancı ekip arkadaşının sunucusu hazır olduğunda tek yapılacak şey
`demo-api/src/main/resources/application.properties` içinde:

```properties
middleware.host=ARKADASIN_BILGISAYARININ_IPSI
middleware.port=5150
```

Kütüphane kodu değişmez — mock ile gerçek sunucu aynı protokolü (PROTOKOL.md) konuşur.

## Kütüphaneyi başka bir Java uygulamasında kullanmak

```java
try (DbmsClient client = new DbmsClient("localhost", 5150, "admin", "admin123")) {
    boolean aktif = client.ping();                                            // Ister_0018
    client.write("okul", "ogrenciler", Map.of("ad", "Ali", "sinif", 3));      // Ister_0017
    List<Map<String, Object>> kayitlar =
            client.read("okul", "ogrenciler", Map.of("sinif", 3));            // Ister_0016
}
```
