package com.example.dbmslib.protocol;

/** Ara katmanın bir isteğe verebileceği sonuç durumları. */
public enum ResponseStatus {
    /** İşlem başarılı. */
    OK,
    /** Kullanıcının bu işleme yetkisi yok (Ister_0015). */
    UNAUTHORIZED,
    /** Diğer tüm hatalar (MongoDB kapalı, geçersiz istek vb.). */
    ERROR
}
