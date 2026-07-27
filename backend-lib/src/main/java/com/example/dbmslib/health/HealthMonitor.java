package com.example.dbmslib.health;

import com.example.dbmslib.client.DbmsClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * "Server aktifliğini gözetleyen" parça (doküman 1.2.3, Ister_0018).
 *
 * Ayrı bir arka plan thread'inde belirli aralıklarla ping() atar,
 * son bilinen durumu saklar ve durum değiştiğinde (aktif <-> pasif)
 * dinleyiciye haber verir.
 */
public class HealthMonitor implements AutoCloseable {

    /** Durum değişikliklerini duymak isteyenlerin uygulayacağı arayüz. */
    public interface Listener {
        void onStatusChanged(boolean serverAlive);
    }

    private final DbmsClient client;
    private final long intervalMillis;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;

    /** null = henüz hiç ping atılmadı; sonrasında son bilinen durum. */
    private volatile Boolean lastKnownAlive;

    public HealthMonitor(DbmsClient client, long intervalMillis, Listener listener) {
        this.client = client;
        this.intervalMillis = intervalMillis;
        this.listener = listener;
        // daemon thread: uygulama kapanırken bu thread onu bekletmez
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dbms-health-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Gözetlemeyi başlatır: hemen bir ping, sonra her intervalMillis'te bir. */
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkOnce, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void checkOnce() {
        boolean alive = client.ping();
        Boolean previous = lastKnownAlive;
        lastKnownAlive = alive;
        boolean changed = previous == null || previous != alive;
        if (changed && listener != null) {
            listener.onStatusChanged(alive);
        }
    }

    /** Son bilinen durum. Henüz hiç kontrol yapılmadıysa false döner. */
    public boolean isServerAlive() {
        return Boolean.TRUE.equals(lastKnownAlive);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
