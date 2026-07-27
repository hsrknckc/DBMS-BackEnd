package com.example.dbms_backend.config;

import com.example.dbmslib.client.DbmsClient;
import com.example.dbmslib.health.HealthMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kütüphaneyi Spring dünyasına tanıtan ayar sınıfı.
 *
 * application.properties'teki middleware.* değerlerini okuyup tek bir
 * DbmsClient nesnesi yaratır; uygulamanın her yerinde bu nesne kullanılır.
 * Uygulama kapanırken Spring, AutoCloseable olduğu için close() metodlarını
 * kendiliğinden çağırır.
 */
@Configuration
public class DbmsClientConfig {

    private static final Logger log = LoggerFactory.getLogger(DbmsClientConfig.class);

    @Bean
    public DbmsClient dbmsClient(@Value("${middleware.host}") String host,
                                 @Value("${middleware.port}") int port,
                                 @Value("${middleware.username}") String username,
                                 @Value("${middleware.password}") String password) {
        log.info("Arka Yüz kütüphanesi ara katmana bağlanacak: {}:{}", host, port);
        return new DbmsClient(host, port, username, password);
    }

    @Bean
    public HealthMonitor healthMonitor(DbmsClient client,
                                       @Value("${middleware.health-check-interval-ms}") long intervalMs) {
        HealthMonitor monitor = new HealthMonitor(client, intervalMs, alive -> {
            if (alive) {
                log.info("Veritabanı sunucusu AKTİF (ara katman üzerinden doğrulandı)");
            } else {
                log.warn("Veritabanı sunucusuna ERİŞİLEMİYOR!");
            }
        });
        monitor.start();
        return monitor;
    }
}
