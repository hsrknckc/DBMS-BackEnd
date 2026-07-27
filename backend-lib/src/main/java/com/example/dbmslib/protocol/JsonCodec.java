package com.example.dbmslib.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON <-> Java nesne dönüşümünü tek yerden yapan yardımcı.
 * Hem kütüphane hem mock sunucu aynı ayarlarla çalışsın diye ortak.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // Karşı taraf ileride mesaja yeni alan eklerse eski kod kırılmasın
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonCodec() {
        // yardımcı sınıf, nesnesi yaratılmaz
    }

    /** Nesneyi tek satır JSON'a çevirir (mesajlar satır bazlı taşındığı için tek satır şart). */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Nesne JSON'a çevrilemedi: " + value, e);
        }
    }

    /** JSON metnini istenen sınıfa çevirir. */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Geçersiz JSON mesajı: " + json, e);
        }
    }
}
