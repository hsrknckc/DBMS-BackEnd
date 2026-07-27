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

## Kimlik bilgileri (ÖNEMLİ — önce oku)

Bu depo **herkese açık**. Bu yüzden gerçek kullanıcı adı/şifre hiçbir zaman
git'e giden bir dosyaya yazılmaz; `application.properties` içindeki
`middleware.username` / `middleware.password` değerleri ortam
değişkenlerinden okunur ve varsayılanı yoktur — ayarlamazsan uygulama
açılışta net bir hatayla durur (yanlış/eksik kimlikle sessizce bağlanmaz).

Gerçek bilgilerini bir kere ayarlamak için:

```bash
cp set-credentials.sh.example set-credentials.sh
# set-credentials.sh içine gerçek MIDDLEWARE_USER / MIDDLEWARE_PASSWORD değerlerini yaz
```

`set-credentials.sh` `.gitignore` içinde olduğu için yanlışlıkla commit'lenmez.
Uygulamayı her başlatmadan önce:

```bash
source set-credentials.sh
```

## Nasıl çalıştırılır?

### 1. Testler

```bash
./gradlew test
```

Testler, sahte ara katman sunucusunu ayağa kaldırıp gerçek TCP bağlantısı
üzerinden ping / yazma / okuma / güncelleme / silme / yetki senaryolarını dener.
Kimlik bilgisi ayarlamana gerek yok, testler kendi sahte kullanıcılarını kullanır.

### 2. Ortak AWS sunucusuna karşı çalıştırma (varsayılan)

`application.properties` içindeki adres varsayılan olarak ekibin ortak
EC2 ara katman sunucusunu gösterir. Kimlik bilgilerini ayarladıktan sonra:

```bash
source set-credentials.sh
./gradlew :demo-api:bootRun
```

Bağlantıyı tek komutla sınamak için:

```bash
./gradlew :backend-lib:runProbe
```

Uçtan uca elle test için (uygulama ayaktayken başka bir terminalden):

```bash
./deneme.sh
```

Bu betik health/write/read/update/delete adımlarını sırayla çalıştırıp
her adımda komutu ve cevabı gösterir.

### 3. Yerel sahte ara katmanla çalışma (internet/EC2 gerekmez)

İki ayrı terminalde:

```bash
# Terminal 1 — sahte ara katman (5150 portunda dinler)
./gradlew :backend-lib:runMockServer

# Terminal 2 — örnek REST API, mock'a yönlendirilmiş
MIDDLEWARE_HOST=localhost MIDDLEWARE_USER=admin MIDDLEWARE_PASSWORD=admin123 ./gradlew :demo-api:bootRun
```

Sahte sunucudaki kullanıcılar: `admin/admin123` (tam yetki), `guest/guest123`
(sadece okuma). Ayrıca gerçek EC2 kullanıcılarının aynısı da tanımlıdır
(`ayse@company.com`, `mehmet@company.com`) — böylece yerelden gerçeğe geçiş
sadece `MIDDLEWARE_HOST` değişikliğiyle olur.

## Kütüphaneyi başka bir Java uygulamasında kullanmak

```java
try (DbmsClient client = new DbmsClient(host, port, username, password)) {
    boolean aktif = client.ping();                                            // Ister_0018
    client.write("okul", "ogrenciler", Map.of("ad", "Ali", "sinif", 3));      // Ister_0017
    List<Map<String, Object>> kayitlar =
            client.read("okul", "ogrenciler", Map.of("sinif", 3));            // Ister_0016
}
```
