package com.spotpulse.controller;

import com.spotpulse.dto.BookingAnalyticsDto;
import com.spotpulse.service.BookingAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Public demonstration endpoint, not an owner-authorized dashboard. */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class BookingAnalyticsController {
    private final BookingAnalyticsService analytics;

    @GetMapping("/bookings")
    public BookingAnalyticsDto bookings(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.getBookings(from, to);
    }
}
