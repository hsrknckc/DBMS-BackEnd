package com.example.dbmslib.exception;

import com.example.dbmslib.protocol.ResponseStatus;

/**
 * Kütüphanenin dışarıya fırlattığı tek hata türü.
 * Kütüphaneyi kullanan uygulama, alt seviyedeki IOException/JSON hatalarıyla
 * uğraşmak yerine sadece bunu yakalar.
 */
public class DbmsException extends RuntimeException {

    /** Hata ara katman cevabından geldiyse durum (UNAUTHORIZED/ERROR); bağlantı hatasıysa null. */
    private final ResponseStatus status;

    public DbmsException(String message) {
        super(message);
        this.status = null;
    }

    public DbmsException(String message, Throwable cause) {
        super(message, cause);
        this.status = null;
    }

    public DbmsException(String message, ResponseStatus status) {
        super(message);
        this.status = status;
    }

    public ResponseStatus getStatus() {
        return status;
    }

    /** Kısa yol: hata bir yetki reddi mi? (örn. HTTP 403'e çevirmek için) */
    public boolean isUnauthorized() {
        return status == ResponseStatus.UNAUTHORIZED;
    }
}
