package com.spotpulse.availability;

import com.spotpulse.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class AvailabilityService {
    private final BookingRepository bookings;
    private final RoomRepository rooms;
    public record Occupied(LocalDateTime start, LocalDateTime end) { }
    public record Snapshot(Long roomId, LocalDateTime from, LocalDateTime to, List<Occupied> occupied) { }

    @Transactional(readOnly = true)
    public Snapshot read(Long roomId, LocalDateTime from, LocalDateTime to) {
        if (roomId == null || roomId < 1 || from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an increasing interval of at most 31 days");
        if (!rooms.existsById(roomId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        var occupied = bookings.occupied(roomId, from, to, PageRequest.of(0, 501));
        if (occupied.size() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many occupied intervals; select a shorter range");
        return new Snapshot(roomId, from, to, occupied.stream().map(b -> new Occupied(
                b.start().isBefore(from) ? from : b.start(), b.end().isAfter(to) ? to : b.end())).toList());
    }
}
