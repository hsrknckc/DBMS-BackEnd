package com.example.dbmslib.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Ara katmandan dönen tek bir cevabın JSON karşılığı.
 *
 * Örnek:
 * {"requestId":"...","status":"OK","message":"2 kayıt bulundu","data":[{...},{...}]}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DbResponse {

    private String requestId;
    private ResponseStatus status;
    private String message;
    /**
     * İşlemin döndürdüğü veri:
     *  - READ            -> kayıt listesi (her eleman bir Map)
     *  - LIST_DATABASES  -> isim listesi (her eleman bir String)
     *  - diğerleri       -> genelde boş
     */
    private List<Object> data;

    public DbResponse() {
    }

    public DbResponse(String requestId, ResponseStatus status, String message, List<Object> data) {
        this.requestId = requestId;
        this.status = status;
        this.message = message;
        this.data = data;
    }

    /** Kısa yol: cevap başarılı mı? */
    public boolean isOk() {
        return status == ResponseStatus.OK;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public ResponseStatus getStatus() { return status; }
    public void setStatus(ResponseStatus status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<Object> getData() { return data; }
    public void setData(List<Object> data) { this.data = data; }
}
