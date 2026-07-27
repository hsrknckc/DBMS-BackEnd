package com.example.dbmslib.protocol;

/**
 * Ara katmana gönderilebilecek işlem türleri.
 * Bu isimler PROTOKOL.md'deki sözleşmenin parçasıdır; ara katman da
 * aynı isimleri tanımak zorundadır.
 */
public enum ActionType {
    /** Veritabanı sunucusu ayakta mı? (Ister_0018) */
    PING,
    /** Koleksiyondan kayıt okuma (Ister_0016) */
    READ,
    /** Koleksiyona yeni kayıt yazma (Ister_0017) */
    WRITE,
    /** Filtreye uyan kayıtları güncelleme */
    UPDATE,
    /** Filtreye uyan kayıtları silme */
    DELETE,
    /** MongoDB'deki veritabanlarını listeleme */
    LIST_DATABASES,
    /** Bir veritabanındaki koleksiyonları listeleme */
    LIST_COLLECTIONS
}
