package com.example.dbmslib;

import com.example.dbmslib.client.DbmsClient;
import com.example.dbmslib.health.HealthMonitor;
import com.example.dbmslib.mock.MockMiddlewareServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthMonitorTest {

    @Test
    void monitorSunucununAktifOldugunuFarkEder() throws Exception {
        try (MockMiddlewareServer server = new MockMiddlewareServer(0)) {
            server.start();

            try (DbmsClient client = new DbmsClient("localhost", server.getPort(), "admin", "admin123")) {
                // Dinleyici ilk "aktif" haberini verince serbest kalacak kilit
                CountDownLatch aliveSignal = new CountDownLatch(1);

                try (HealthMonitor monitor = new HealthMonitor(client, 100, alive -> {
                    if (alive) {
                        aliveSignal.countDown();
                    }
                })) {
                    monitor.start();

                    assertTrue(aliveSignal.await(5, TimeUnit.SECONDS),
                            "Monitor 5 saniye içinde sunucunun aktif olduğunu bildirmeliydi");
                    assertTrue(monitor.isServerAlive());
                }
            }
        }
    }
}
