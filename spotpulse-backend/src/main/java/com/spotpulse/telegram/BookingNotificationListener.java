package com.spotpulse.telegram;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component @RequiredArgsConstructor
public class BookingNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationListener.class);
    private final TelegramNotificationEligibility eligibility;
    private final TelegramClient client;

    // Synchronous after commit: no detached task, lost Future or inherited security context.
    // TelegramClient bounds delivery to 30 seconds; failure cannot undo the booking commit.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(BookingNotification event) {
        outcome(event, "triggered", "none");
        try {
            var recipient = eligibility.recipient(event.userId());
            if (recipient.skipReason() != null) {
                outcome(event, "skipped", recipient.skipReason());
                return;
            }
            client.send(recipient.chatId(), "SpotPulse booking #" + event.bookingId() + " "
                    + event.status().name().toLowerCase(java.util.Locale.ROOT) + ".\n"
                    + event.startTime() + " – " + event.endTime());
            outcome(event, "sent", "none");
        } catch (TelegramClient.Failure failure) {
            outcome(event, "failed", switch (failure.status) {
                case 401 -> "api_unauthorized";
                case 403 -> "api_forbidden";
                case 429 -> "api_rate_limited";
                case 0 -> "transport_or_timeout";
                default -> "api_rejected";
            });
        } catch (Exception ignored) {
            // Never log exception messages/causes: HTTP errors may contain credential URLs.
            outcome(event, "failed", "internal_error");
        }
    }

    private void outcome(BookingNotification event, String outcome, String category) {
        log.info("telegram_booking_notification event_id={} booking_id={} event_type={} outcome={} category={}",
                event.eventId(), event.bookingId(), event.status(), outcome, category);
    }
}
