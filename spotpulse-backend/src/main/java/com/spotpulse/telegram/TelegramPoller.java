package com.spotpulse.telegram;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.*;

@Component @RequiredArgsConstructor
public class TelegramPoller {
    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);
    private final TelegramClient client;
    private final TelegramPollingStore store;
    private final TelegramRuntime runtime;
    private final String owner = UUID.randomUUID().toString();
    // Exactly one long-lived task, no request-driven queue or unbounded worker creation.
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().name("telegram-poller").daemon().factory());
    private volatile boolean stopping;
    private volatile Long botId;

    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (client.configured()) worker.submit(this::run); }

    private void run() {
        int failures = 0;
        try {
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                try {
                    var bot = runtime.bot();
                    if (bot == null) {
                        var me = client.me();
                        String username = me.path("username").asText("");
                        if (!me.path("id").isIntegralNumber() || !me.path("is_bot").asBoolean()
                                || !username.matches("[A-Za-z0-9_]{5,32}")) throw new TelegramClient.Failure(0, 30);
                        botId = me.path("id").asLong(); runtime.ready(botId, username);
                    }
                    var lease = store.acquire(botId, owner);
                    if (!lease.acquired()) { pause(5); continue; }
                    var replies = store.process(botId, owner, client.updates(lease.offset()));
                    for (var reply : replies) {
                        if (stopping || !store.acquire(botId, owner).acquired()) break;
                        try { client.send(reply.chatId(), reply.text()); }
                        catch (TelegramClient.Failure failure) {
                            if (failure.status == 429) pause(failure.retrySeconds);
                            // Confirmation is best effort; committed linking remains visible in the profile.
                        }
                    }
                    failures = 0;
                } catch (TelegramClient.Failure failure) {
                    runtime.unavailable();
                    if (failures++ == 0) log.warn("Telegram unavailable (status {}); retrying in background", failure.status);
                    pause(Math.max(failure.retrySeconds, Math.min(60, 1L << Math.min(failures, 6))));
                } catch (Exception ignored) {
                    if (failures++ == 0) log.warn("Telegram update processing unavailable; retrying in background");
                    pause(Math.min(60, 1L << Math.min(failures, 6)));
                }
            }
        } finally {
            runtime.unavailable();
            if (botId != null) {
                // Clear interruption so the final short database transaction can release the lease.
                Thread.interrupted();
                try { store.release(botId, owner); } catch (Exception ignored) { /* Lease also expires. */ }
            }
        }
    }
    private void pause(long seconds) {
        try { TimeUnit.SECONDS.sleep(Math.max(1, Math.min(seconds, 86400))); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @PreDestroy public void stop() {
        stopping = true; worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
