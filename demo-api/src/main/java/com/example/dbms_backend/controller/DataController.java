package com.example.dbms_backend.controller;

import com.example.dbmslib.client.DbmsClient;
import com.example.dbmslib.exception.DbmsException;
import com.example.dbmslib.health.HealthMonitor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kütüphanenin "API uygulamasında kullanılmasını" gösteren REST uçları.
 *
 * Dikkat: Bu sınıfta MongoDB'ye dair TEK SATIR yok — sadece DbmsClient
 * metodları çağrılıyor. Kütüphanenin bütün amacı buydu (doküman 1.2.3).
 *
 * Deneme örnekleri (uygulama ayaktayken):
 *   curl http://localhost:8080/api/health
 *   curl -X POST http://localhost:8080/api/okul/ogrenciler \
 *        -H "Content-Type: application/json" -d '{"ad":"Ali","sinif":3}'
 *   curl "http://localhost:8080/api/okul/ogrenciler?sinif=3"
 */
@RestController
@RequestMapping("/api")
public class DataController {

    private final DbmsClient client;
    private final HealthMonitor monitor;

    public DataController(DbmsClient client, HealthMonitor monitor) {
        this.client = client;
        this.monitor = monitor;
    }

    /**
     * Sunucu aktifliği (Ister_0018): anlık kontrol + monitörün son bilgisi.
     * "detail" alanı, aktif değilse sebebini söyler (ulaşılamıyor mu, kimlik mi
     * reddedildi) — yanlış yerde hata aramamak için.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        DbmsClient.HealthStatus status = client.checkHealth();
        return Map.of(
                "serverAlive", status.alive(),
                "detail", status.detail(),
                "monitorLastStatus", monitor.isServerAlive());
    }

    /** MongoDB'deki veritabanlarını listeler. */
    @GetMapping("/databases")
    public List<String> listDatabases() {
        return client.listDatabases();
    }

    /** Bir veritabanındaki koleksiyonları listeler. */
    @GetMapping("/databases/{db}/collections")
    public List<String> listCollections(@PathVariable String db) {
        return client.listCollections(db);
    }

    /**
     * Kayıt okuma (Ister_0016). Query parametreleri filtre olur:
     * GET /api/okul/ogrenciler?sinif=3  ->  filter = {"sinif": "3"}
     */
    @GetMapping("/{db}/{collection}")
    public List<Map<String, Object>> read(@PathVariable String db,
                                          @PathVariable String collection,
                                          @RequestParam Map<String, String> queryParams) {
        return client.read(db, collection, parseFilter(queryParams));
    }

    /** Kayıt yazma (Ister_0017). Gövdedeki JSON olduğu gibi kaydedilir. */
    @PostMapping("/{db}/{collection}")
    public ResponseEntity<Map<String, String>> write(@PathVariable String db,
                                                     @PathVariable String collection,
                                                     @RequestBody Map<String, Object> document) {
        client.write(db, collection, document);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Kayıt eklendi"));
    }

    /**
     * Kayıt güncelleme. Gövde iki parçalıdır:
     * {"filter": {"ad": "Ali"}, "newValues": {"sinif": 4}}
     */
    @PutMapping("/{db}/{collection}")
    public Map<String, String> update(@PathVariable String db,
                                      @PathVariable String collection,
                                      @RequestBody UpdateBody body) {
        client.update(db, collection, body.filter(), body.newValues());
        return Map.of("message", "Güncelleme yapıldı");
    }

    /** Kayıt silme. Query parametreleri hangi kayıtların silineceğini seçer. */
    @DeleteMapping("/{db}/{collection}")
    public Map<String, String> delete(@PathVariable String db,
                                      @PathVariable String collection,
                                      @RequestParam Map<String, String> queryParams) {
        client.delete(db, collection, parseFilter(queryParams));
        return Map.of("message", "Silme yapıldı");
    }

    /**
     * HTTP query parametreleri HER ZAMAN metin olarak gelir: ?sinif=3 -> "3".
     * Ara katman ise filtreyi tip duyarlı karşılaştırır; kayıtta sayı 3 varken
     * metin "3" ile arama yapılırsa hiçbir kayıt eşleşmez.
     *
     * Bu yüzden sayıya/boolean'a benzeyen değerleri gerçek tipine çeviriyoruz.
     * Sınır: gerçekten metin olarak saklanmış "3" gibi bir değeri artık
     * ?alan=3 ile bulamazsın; öyle bir ihtiyaç olursa filtreyi gövdede
     * JSON olarak alan ayrı bir uç eklenmeli.
     */
    private static Map<String, Object> parseFilter(Map<String, String> queryParams) {
        Map<String, Object> filter = new HashMap<>();
        queryParams.forEach((key, value) -> filter.put(key, parseValue(value)));
        return filter;
    }

    /** "3" -> 3, "2.5" -> 2.5, "true" -> true, "Ali" -> "Ali" */
    private static Object parseValue(String raw) {
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException notAnInteger) {
            // tam sayı değil, ondalık olabilir
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException notANumber) {
            return raw; // düz metin
        }
    }

    /** PUT gövdesinin şekli. */
    public record UpdateBody(Map<String, Object> filter, Map<String, Object> newValues) {
    }

    /**
     * Kütüphane hatalarını uygun HTTP koduna çevirir.
     *
     * Ayrım şuna dayanır: ara katman CEVAP VERDİ mi?
     *   UNAUTHORIZED -> 403: cevap verdi, yetki yok.
     *   ERROR        -> 400: cevap verdi, isteğimizi reddetti (örneğin tip
     *                        doğrulaması: "Field 'yas' must be of type int").
     *                        Sunucu sağlıklı, hatalı olan gönderilen veri.
     *   status yok   -> 502: hiç cevap alınamadı (ara katman kapalı/erişilemez).
     *
     * Bu ayrım önemli: hepsine 502 demek, "sunucu bozuk, sonra tekrar dene"
     * anlamına gelir ve istemciyi yanlış yöne sevk eder.
     */
    @ExceptionHandler(DbmsException.class)
    public ResponseEntity<Map<String, String>> handleDbmsError(DbmsException e) {
        HttpStatus status;
        if (e.isUnauthorized()) {
            status = HttpStatus.FORBIDDEN;
        } else if (e.getStatus() != null) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
