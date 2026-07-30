package com.example.dbmslib.client;

import com.example.dbmslib.connection.MiddlewareConnection;
import com.example.dbmslib.exception.DbmsException;
import com.example.dbmslib.protocol.ActionType;
import com.example.dbmslib.protocol.DbRequest;
import com.example.dbmslib.protocol.DbResponse;
import com.example.dbmslib.protocol.ResponseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ARKA YÜZ KÜTÜPHANESİNİN ANA SINIFI.
 *
 * Kütüphaneyi kullanan uygulama sadece bu sınıfı görür; MongoDB'yi,
 * TCP protokolünü veya JSON formatını bilmek zorunda değildir
 * (dokümanın 1.2 hedefi: "mongodb altyapısını bilmeksizin işlem yapılması").
 *
 * İster eşleşmeleri:
 *  - read()          -> Ister_0016 (ara katman aracılığıyla okuma)
 *  - write()         -> Ister_0017 (ara katman aracılığıyla yazma)
 *  - ping()          -> Ister_0018 (sunucu aktifliği kontrolü)
 *  - tüm haberleşme  -> Ister_0019 (TCP/IP, MiddlewareConnection üzerinden)
 *
 * Kullanım örneği:
 *   try (DbmsClient client = new DbmsClient("localhost", 5150, "admin", "admin123")) {
 *       client.write("okul", "ogrenciler", Map.of("ad", "Ali", "sinif", 3));
 *       List<Map<String, Object>> kayitlar = client.read("okul", "ogrenciler", Map.of("sinif", 3));
 *   }
 */
public class DbmsClient implements AutoCloseable {

    private final MiddlewareConnection connection;
    private final String username;
    private final String password;

    public DbmsClient(String host, int port, String username, String password) {
        this.connection = new MiddlewareConnection(host, port);
        this.username = username;
        this.password = password;
    }

    /**
     * Sunucu aktiflik kontrolünün ayrıntılı sonucu.
     *
     * @param alive  sunucu ve veritabanı erişilebilir mi
     * @param detail insan okunur açıklama (neden başarısız olduğu dahil)
     */
    public record HealthStatus(boolean alive, String detail) {
    }

    /**
     * Veritabanı sunucusu aktif mi? (Ister_0018)
     * Hata fırlatmaz; sunucuya ulaşılamıyorsa bu zaten sorulan sorunun
     * cevabıdır -> false.
     */
    public boolean ping() {
        return checkHealth().alive();
    }

    /**
     * ping() ile aynı kontrolü yapar ama BAŞARISIZLIK SEBEBİNİ de döner.
     *
     * Bu ayrım pratikte önemli: yalnızca "false" görmek, "sunucu kapalı" ile
     * "şifre yanlış" durumlarını birbirine karıştırır ve yanlış yerde hata
     * aranmasına yol açar.
     */
    public HealthStatus checkHealth() {
        try {
            DbResponse response = connection.send(newRequest(ActionType.PING));
            if (response.isOk()) {
                return new HealthStatus(true, "Sunucu aktif");
            }
            if (response.getStatus() == ResponseStatus.UNAUTHORIZED) {
                return new HealthStatus(false,
                        "Sunucuya ulaşıldı fakat kimlik reddedildi (kullanıcı adı tam e-posta mı?): "
                                + response.getMessage());
            }
            return new HealthStatus(false, "Sunucu hata döndü: " + response.getMessage());
        } catch (DbmsException unreachable) {
            return new HealthStatus(false, "Ara katmana ulaşılamıyor: " + unreachable.getMessage());
        }
    }

    /**
     * Koleksiyondan kayıt okur (Ister_0016).
     *
     * @param filter hangi kayıtlar? Boş/null ise hepsi. Örn: Map.of("sinif", 3)
     * @return kayıt listesi; her kayıt alan-adı -> değer eşlemesi
     */
    public List<Map<String, Object>> read(String database, String collection, Map<String, Object> filter) {
        DbRequest request = newRequest(ActionType.READ);
        request.setDatabase(database);
        request.setCollection(collection);
        request.setFilter(filter);
        return toRecordList(sendChecked(request));
    }

    /** Koleksiyona yeni kayıt yazar (Ister_0017). */
    public void write(String database, String collection, Map<String, Object> document) {
        DbRequest request = newRequest(ActionType.WRITE);
        request.setDatabase(database);
        request.setCollection(collection);
        request.setDocument(document);
        sendChecked(request);
    }

    /**
     * Filtreye uyan kayıtların alanlarını günceller.
     *
     * @param newValues değişecek alanlar, örn: Map.of("sinif", 4)
     */
    public void update(String database, String collection,
                       Map<String, Object> filter, Map<String, Object> newValues) {
        DbRequest request = newRequest(ActionType.UPDATE);
        request.setDatabase(database);
        request.setCollection(collection);
        request.setFilter(filter);
        request.setDocument(newValues);
        sendChecked(request);
    }

    /** Filtreye uyan kayıtları siler. */
    public void delete(String database, String collection, Map<String, Object> filter) {
        DbRequest request = newRequest(ActionType.DELETE);
        request.setDatabase(database);
        request.setCollection(collection);
        request.setFilter(filter);
        sendChecked(request);
    }

    /** MongoDB'de mevcut veritabanlarının adlarını listeler. */
    public List<String> listDatabases() {
        return toStringList(sendChecked(newRequest(ActionType.LIST_DATABASES)));
    }

    /** Bir veritabanındaki koleksiyon adlarını listeler. */
    public List<String> listCollections(String database) {
        DbRequest request = newRequest(ActionType.LIST_COLLECTIONS);
        request.setDatabase(database);
        return toStringList(sendChecked(request));
    }

    private DbRequest newRequest(ActionType action) {
        return new DbRequest(action, username, password);
    }

    /** İsteği gönderir; cevap OK değilse anlamlı bir hata fırlatır. */
    private DbResponse sendChecked(DbRequest request) {
        DbResponse response = connection.send(request);
        if (!response.isOk()) {
            throw new DbmsException(
                    request.getAction() + " başarısız: " + response.getMessage(),
                    response.getStatus());
        }
        return response;
    }

    /**
     * Cevaptaki data alanını kayıt listesine çevirir.
     *
     * Ara katman beklenmedik bir şey döndürürse (örneğin kayıt listesi yerine
     * düz metin) ham ClassCastException yerine anlaşılır bir DbmsException
     * fırlatılır; kütüphaneyi kullanan uygulama tek tip hata yakalamaya
     * devam edebilsin diye.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRecordList(DbResponse response) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (response.getData() != null) {
            for (Object item : response.getData()) {
                if (!(item instanceof Map)) {
                    throw new DbmsException(
                            "Ara katmandan beklenmeyen cevap: kayıt listesi bekleniyordu, gelen: "
                                    + (item == null ? "null" : item.getClass().getSimpleName()));
                }
                records.add((Map<String, Object>) item);
            }
        }
        return records;
    }

    private List<String> toStringList(DbResponse response) {
        List<String> names = new ArrayList<>();
        if (response.getData() != null) {
            for (Object item : response.getData()) {
                names.add(String.valueOf(item));
            }
        }
        return names;
    }

    @Override
    public void close() {
        connection.close();
    }
}
