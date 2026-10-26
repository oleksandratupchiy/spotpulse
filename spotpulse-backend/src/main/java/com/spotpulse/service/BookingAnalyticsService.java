package com.spotpulse.service;

import com.spotpulse.dto.BookingAnalyticsDto;
import com.spotpulse.dto.BookingAnalyticsDto.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.repository.BookingAnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.spotpulse.security.AccessPolicy;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BookingAnalyticsService {
    private final AccessPolicy access;
    public static final int MAX_DAYS = 366;
    public static final int POPULAR_ROOMS_LIMIT = 10;
    private final BookingAnalyticsRepository repository;

    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Cacheable(cacheNames = "bookingAnalytics", key = "@readCaches.key('bookingAnalytics', @access.analyticsScope(), #from, #to)", condition = "@readCaches.allowed()")
    public BookingAnalyticsDto getBookings(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9998
                || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose an inclusive date range of 1 to 366 days within years 0001–9998");
        }
        var start = from.atStartOfDay();
        var until = to.plusDays(1).atStartOfDay();
        Map<LocalDate, long[]> counts = new HashMap<>();
        long confirmed = 0;
        long cancelled = 0;
        for (var row : repository.daily(start, until, access.analyticsOwnerId())) {
            var day = counts.computeIfAbsent(row.getBookingDate(), ignored -> new long[2]);
            if (row.getStatus() == BookingStatus.CONFIRMED) {
                day[0] = row.getCount();
                confirmed += row.getCount();
            } else if (row.getStatus() == BookingStatus.CANCELLED) {
                day[1] = row.getCount();
                cancelled += row.getCount();
            }
        }
        var daily = new ArrayList<Day>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            long[] day = counts.getOrDefault(date, new long[2]);
            daily.add(new Day(date, day[0], day[1]));
        }
        var popular = repository.popularRooms(start, until, BookingStatus.CONFIRMED, access.analyticsOwnerId(),
                        PageRequest.of(0, POPULAR_ROOMS_LIMIT)).stream()
                .map(row -> new PopularRoom(row.getRoomId(), row.getRoomName(), row.getSpaceId(),
                        row.getSpaceName(), row.getCount())).toList();
        return new BookingAnalyticsDto(from, to, "BOOKING_START_DATE", confirmed + cancelled,
                new StatusCounts(confirmed, cancelled), daily, POPULAR_ROOMS_LIMIT, popular);
    }
}
