package com.spotpulse.availability;

import com.spotpulse.telegram.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "spotpulse.availability-alerts.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class AvailabilityAlertDispatcher {
    private final AvailabilityAlertStore store;
    private final TelegramNotificationEligibility eligibility;
    private final TelegramClient client;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("availability-alert-delivery").daemon().factory());

    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    public void schedule() {
        if (!running.compareAndSet(false, true)) return;
        try { worker.submit(() -> { try { process(); } finally { running.set(false); } }); }
        catch (RejectedExecutionException stopped) { running.set(false); }
    }
    public void process() {
        try {
            for (Long id : store.due()) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    var delivery = store.claim(id);
                    if (delivery == null) continue;
                    try {
                        var recipient = eligibility.recipient(delivery.userId());
                        if (recipient.skipReason() != null) { store.finish(delivery, recipient.skipReason(), 60); continue; }
                        client.send(recipient.chatId(), delivery.text());
                        store.finish(delivery, null, 0);
                    } catch (TelegramClient.Failure failure) {
                        store.finish(delivery, failure.status == 429 ? "rate_limited" : "delivery_failed",
                                Math.max(failure.retrySeconds, backoff(delivery.attempts())));
                    } catch (Exception ignored) {
                        // Never log raw exceptions, messages, URLs or recipients. The lease also recovers failures here.
                        store.finish(delivery, "delivery_unconfirmed", backoff(delivery.attempts()));
                    }
                } catch (Exception ignored) {
                    LoggerFactory.getLogger(getClass()).warn("Availability alert processing deferred; retry remains scheduled");
                }
            }
        } catch (Exception ignored) {
            LoggerFactory.getLogger(getClass()).warn("Availability alert scan deferred");
        }
    }
    private static long backoff(int attempts) { return Math.min(3600, 5L << Math.min(attempts, 9)); }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
