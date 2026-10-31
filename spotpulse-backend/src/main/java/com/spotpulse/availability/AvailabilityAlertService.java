package com.spotpulse.availability;

import com.spotpulse.dto.BookingCreateDto;
import com.spotpulse.repository.*;
import com.spotpulse.security.AccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;

@Service @RequiredArgsConstructor @PreAuthorize("isAuthenticated()")
public class AvailabilityAlertService {
    private final AvailabilityAlertRepository alerts;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final AccessPolicy access;
    private final AvailabilityService availability;
    public record Input(Long roomId, LocalDateTime startTime, LocalDateTime endTime) { }
    public record View(Long id, Long roomId, String roomName, String spaceName, String city,
                       LocalDateTime startTime, LocalDateTime endTime, AvailabilityAlert.Status status,
                       Instant createdAt, Instant notifiedAt, String deliveryReason) { }
    static View view(AvailabilityAlert a) {
        var status = a.active() && !a.getStartTime().isAfter(LocalDateTime.now()) ? AvailabilityAlert.Status.EXPIRED : a.getStatus();
        return new View(a.getId(), a.getRoom().getId(), a.getRoom().getName(), a.getRoom().getSpace().getName(),
                a.getRoom().getSpace().getCity(), a.getStartTime(), a.getEndTime(), status, a.getCreatedAt(), a.getNotifiedAt(), a.getDeliveryReason());
    }
    @Transactional
    public View create(Input input) {
        var start = input.startTime() == null ? null : input.startTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var end = input.endTime() == null ? null : input.endTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (input.roomId() == null || start == null || end == null || !start.isAfter(LocalDateTime.now()) || !end.isAfter(start))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a future start and an end after the start.");
        if (!new BookingCreateDto(input.roomId(), null, start, end, null).isDurationValid())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bookings and availability alerts can last up to 12 hours.");
        // Same room lock as booking writes: serialize duplicate requests and avoid missed cancellations.
        // Rooms are publicly readable in SpotPulse; no additional ownership restriction applies.
        var room = rooms.findByIdForUpdate(input.roomId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        Long userId = access.userId();
        var existing = alerts.findByUserIdAndRoomIdAndStartTimeAndEndTimeAndActiveMarker(userId, room.getId(), start, end, "ACTIVE");
        if (existing.isPresent()) return view(existing.get());
        if (availability.read(room.getId(), start, end).occupied().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This time slot is already available. You can book it now; no alert is needed.");
        var alert = new AvailabilityAlert(); alert.setRoom(room); alert.setUser(users.findById(userId).orElseThrow());
        alert.setStartTime(start); alert.setEndTime(end);
        return view(alerts.saveAndFlush(alert));
    }
    @Transactional(readOnly = true)
    public Page<View> list(Long roomId, LocalDateTime start, LocalDateTime end, boolean activeOnly, Pageable page) {
        return alerts.own(access.userId(), roomId, start, end, activeOnly, LocalDateTime.now(), page).map(AvailabilityAlertService::view);
    }
    @Transactional
    public void cancel(Long id) {
        var alert = alerts.lock(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
        if (!alert.getUser().getId().equals(access.userId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found");
        if (alert.active()) alert.finish(alert.getStartTime().isAfter(LocalDateTime.now())
                ? AvailabilityAlert.Status.CANCELLED : AvailabilityAlert.Status.EXPIRED);
    }
}
