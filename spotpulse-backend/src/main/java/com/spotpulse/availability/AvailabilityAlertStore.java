package com.spotpulse.availability;

import com.spotpulse.repository.RoomRepository;
import com.spotpulse.seo.SpaceSeo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service @RequiredArgsConstructor
public class AvailabilityAlertStore {
    private final AvailabilityAlertRepository alerts;
    private final RoomRepository rooms;
    private final AvailabilityService availability;
    private final SpaceSeo seo;
    @Value("${spotpulse.auth.frontend-origin:http://localhost:5173}") private String frontendOrigin;
    @Value("${spotpulse.auth.ui-base-path:}") private String uiBasePath;
    public record Delivery(Long id, String token, Long userId, String text, int attempts) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void wake(Long roomId) { alerts.wake(roomId, Instant.now(), AvailabilityAlert.Status.ACTIVE); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> due() {
        alerts.expire(LocalDateTime.now(), AvailabilityAlert.Status.EXPIRED);
        return alerts.due(Instant.now(), PageRequest.of(0, 50));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Delivery claim(Long id) {
        var roomId = alerts.roomId(id).orElse(null);
        if (roomId == null || rooms.findByIdForUpdate(roomId).isEmpty()) return null;
        // Lock room, then subscription. No booking row locks and no network inside this transaction.
        var a = alerts.lock(id).orElse(null);
        var now = Instant.now();
        if (a == null || !a.active() || a.getNextAttemptAt().isAfter(now)) return null;
        if (!a.getStartTime().isAfter(LocalDateTime.now())) { a.finish(AvailabilityAlert.Status.EXPIRED); return null; }
        if (!availability.read(roomId, a.getStartTime(), a.getEndTime()).occupied().isEmpty()) {
            a.setStatus(AvailabilityAlert.Status.ACTIVE); a.setLeaseToken(null); a.setDeliveryReason(null);
            a.setNextAttemptAt(now.plusSeconds(30)); return null;
        }
        a.setStatus(AvailabilityAlert.Status.READY);
        a.setLeaseToken(UUID.randomUUID().toString());
        // Lease exceeds TelegramClient's complete 30-second request bound. Recover crashed claims.
        a.setNextAttemptAt(now.plusSeconds(120)); a.setAttempts(a.getAttempts() + 1);
        var room = a.getRoom(); var space = room.getSpace();
        String url = seo.publicUrl(uiBasePath + "/rooms/" + roomId);
        if (url == null) url = frontendOrigin.replaceAll("/+$", "") + "/rooms/" + roomId;
        var format = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH);
        String text = "Room available: " + name(room.getName()) + "\n" + name(space.getName()) + " · " + name(space.getCity())
                + "\n" + a.getStartTime().format(format) + " – " + a.getEndTime().format(format)
                + "\nTimes as entered in SpotPulse\n" + url + "\nAvailability is not a reservation. Book in SpotPulse; availability may change.";
        return new Delivery(id, a.getLeaseToken(), a.getUser().getId(), text, a.getAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Delivery delivery, String reason, long retrySeconds) {
        var a = alerts.lock(delivery.id()).orElse(null);
        if (a == null || a.getStatus() != AvailabilityAlert.Status.READY || !delivery.token().equals(a.getLeaseToken())) return;
        a.setLeaseToken(null); a.setDeliveryReason(reason);
        if (reason == null) { a.setNotifiedAt(Instant.now()); a.finish(AvailabilityAlert.Status.SENT); }
        else a.setNextAttemptAt(Instant.now().plusSeconds(Math.max(5, Math.min(86400, retrySeconds))));
    }
    private static String name(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").strip();
    }
}
