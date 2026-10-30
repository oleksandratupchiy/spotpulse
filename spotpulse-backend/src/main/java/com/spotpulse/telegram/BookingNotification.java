package com.spotpulse.telegram;

import com.spotpulse.model.Booking;
import com.spotpulse.model.enums.BookingStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/** Immutable snapshot: never pass managed entities to an after-commit listener. */
public record BookingNotification(UUID eventId, Long bookingId, Long userId, BookingStatus status,
                                  LocalDateTime startTime, LocalDateTime endTime) {
    public static BookingNotification of(Booking booking) {
        return new BookingNotification(UUID.randomUUID(), booking.getId(), booking.getUser().getId(),
                booking.getStatus(), booking.getStartTime(), booking.getEndTime());
    }
}
