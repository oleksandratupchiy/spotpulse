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
            var format = java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", java.util.Locale.ENGLISH);
            var cancelled = event.status() == com.spotpulse.model.enums.BookingStatus.CANCELLED;
            client.send(recipient.chatId(), "Your booking for " + displayName(event.roomName())
                    + (cancelled ? " was cancelled." : " is confirmed.") + "\n"
                    + displayName(event.spaceName()) + "\n"
                    + event.startTime().format(format) + " – " + event.endTime().format(format)
                    + "\nTimes as booked · " + (cancelled ? "Cancelled" : "Confirmed"));
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

    // Names are user supplied; keep each on one line in plain-text Telegram messages.
    private String displayName(String name) {
        return name.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").strip();
    }

    private void outcome(BookingNotification event, String outcome, String category) {
        log.info("telegram_booking_notification event_id={} booking_id={} event_type={} outcome={} category={}",
                event.eventId(), event.bookingId(), event.status(), outcome, category);
    }
}
