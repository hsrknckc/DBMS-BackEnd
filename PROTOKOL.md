# Arka Yüz ↔ Ara Katman TCP Protokolü (v1)

Bu doküman, **Arka Yüz kütüphanesi** ile **Ara Katman sunucusu** arasındaki
haberleşme sözleşmesidir. Ara Katman'ı yazan ekip arkadaşının sunucusu bu
sözleşmeye uyduğu sürece iki parça sorunsuz çalışır. (Ön Yüz de isterse aynı
protokolle ara katmana bağlanabilir.)

## 1. Taşıma katmanı (Ister_0019, Ister_0010)

- Haberleşme **ham TCP soketi** üzerinden yapılır.
- Ara Katman bir **TCP sunucusudur**; varsayılan port: **5150** (yapılandırılabilir olmalı).
- Her mesaj **tek satır JSON**'dur, **UTF-8** kodlanır ve **`\n` (newline) ile biter**.
- Akış istek-cevap düzenindedir: istemci bir satır yazar, sunucu bir satır cevap yazar.
- Bir bağlantı üzerinden art arda birden çok istek gönderilebilir (bağlantı açık kalır).
- Sunucu, istemci bağlantıyı kapatana kadar satır okumaya devam etmelidir.

## 2. İstek formatı (istemci → sunucu)

```json
{"requestId":"a1b2c3","action":"READ","username":"ozan","password":"sifre","database":"okul","collection":"ogrenciler","filter":{"sinif":3},"document":null}
```

| Alan | Tip | Zorunlu | Açıklama |
|---|---|---|---|
| `requestId` | string | evet | İstemcinin ürettiği benzersiz kimlik (UUID). Cevapta aynen geri döner. |
| `action` | string | evet | Aşağıdaki işlem türlerinden biri. |
| `username` | string | evet | İsteği yapan kullanıcı. Ara katman yetki kontrolünü buna göre yapar (Ister_0015). |
| `password` | string | evet | Kullanıcının şifresi. |
| `database` | string | işleme göre | Hedef veritabanı adı. |
| `collection` | string | işleme göre | Hedef koleksiyon adı. |
| `filter` | object | hayır | Hangi kayıtlar? Örn. `{"sinif":3}`. Boş/yok ise tüm kayıtlar. |
| `document` | object | işleme göre | WRITE'ta eklenecek kayıt; UPDATE'te değişecek alanlar. |

`null` olan alanlar JSON'a hiç yazılmayabilir.

## 3. Cevap formatı (sunucu → istemci)

```json
{"requestId":"a1b2c3","status":"OK","message":"2 kayıt bulundu","data":[{"ad":"Ali","sinif":3},{"ad":"Ayşe","sinif":3}]}
```

| Alan | Tip | Açıklama |
|---|---|---|
| `requestId` | string | İstekteki değerin aynısı. |
| `status` | string | `OK`, `UNAUTHORIZED` veya `ERROR`. |
| `message` | string | İnsan okunur açıklama (hata mesajı ya da özet). |
| `data` | array | İşlemin döndürdüğü veri (aşağıya bak); yoksa `null`/eksik. |

## 4. İşlem türleri (action)

| action | Ne yapar | Kullanılan alanlar | `data` içeriği |
|---|---|---|---|
| `PING` | MongoDB erişilebilir mi kontrol eder (Ister_0018) | — | yok |
| `READ` | Filtreye uyan kayıtları döner (Ister_0016) | database, collection, filter | kayıt listesi `[{...},{...}]` |
| `WRITE` | Yeni kayıt ekler (Ister_0017) | database, collection, document | yok |
| `UPDATE` | Filtreye uyan kayıtların alanlarını `document`'taki değerlerle değiştirir | database, collection, filter, document | yok |
| `DELETE` | Filtreye uyan kayıtları siler | database, collection, filter | yok |
| `LIST_DATABASES` | Mevcut veritabanı adlarını döner (Ister_0008) | — | isim listesi `["okul","stok"]` |
| `LIST_COLLECTIONS` | Veritabanındaki koleksiyon adlarını döner | database | isim listesi |

## 5. Hata kuralları

- Kullanıcı adı/şifre yanlış **veya** kullanıcının o işleme yetkisi yok →
  `status: "UNAUTHORIZED"` (Ister_0015).
- MongoDB kapalı, alan eksik, JSON bozuk vb. → `status: "ERROR"` ve `message`
  içinde sebep.
- Sunucu bir satırı hiç anlayamasa bile bağlantıyı koparmamalı, `ERROR` cevabı
  yazmalıdır.
- `PING` isteğinde MongoDB'ye ulaşılamıyorsa `status: "ERROR"` dönmelidir
  (Arka Yüz bunu "sunucu pasif" olarak yorumlar).

## 6. Örnek oturum

```
İstemci → {"requestId":"1","action":"PING","username":"admin","password":"admin123"}
Sunucu  → {"requestId":"1","status":"OK","message":"MongoDB aktif"}
İstemci → {"requestId":"2","action":"WRITE","username":"admin","password":"admin123","database":"okul","collection":"ogrenciler","document":{"ad":"Ali","sinif":3}}
Sunucu  → {"requestId":"2","status":"OK","message":"1 kayıt eklendi"}
İstemci → {"requestId":"3","action":"READ","username":"admin","password":"admin123","database":"okul","collection":"ogrenciler","filter":{"sinif":3}}
Sunucu  → {"requestId":"3","status":"OK","message":"1 kayıt bulundu","data":[{"ad":"Ali","sinif":3}]}
```
