package com.spotpulse.premium;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

/** Internal aggregate cache. The public service checks ownership, flags and entitlement first. */
@Service @RequiredArgsConstructor
public class RoomPerformanceReads {
    private final RoomPerformanceRepository repository;
    @Cacheable(cacheNames = "bookingAnalytics", key = "@readCaches.key('bookingAnalytics', 'premium-rooms', #id, #from, #to)", condition = "@readCaches.allowed()")
    @Transactional(readOnly = true)
    public RoomPerformanceDto read(Long id, LocalDate from, LocalDate to) {
        var rooms = repository.compare(id, from.atStartOfDay(), to.plusDays(1).atStartOfDay()).stream().map(row -> {
            long total = row.getConfirmed() + row.getCancelled();
            return new RoomPerformanceDto.RoomPerformance(row.getRoomId(), row.getRoomName(), row.getConfirmed(),
                    row.getCancelled(), total == 0 ? 0 : 100.0 * row.getCancelled() / total);
        }).toList();
        return new RoomPerformanceDto(id, from, to, "BOOKING_START_DATE", rooms);
    }
}
