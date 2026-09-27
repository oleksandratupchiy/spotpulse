package com.spotpulse.controller;

import com.spotpulse.dto.BookingCreateDto;
import com.spotpulse.dto.BookingDto;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /** GET /api/bookings?page=0&size=10  або  ?userId=1  або  ?roomId=2  або  ?status=CONFIRMED */
    @GetMapping
    public Page<BookingDto> getAll(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) BookingStatus status,
            Pageable pageable) {
        return bookingService.getAll(userId, roomId, status, pageable);
    }

    /** GET /api/bookings/{id} */
    @GetMapping("/{id}")
    public BookingDto getById(@PathVariable Long id) {
        return bookingService.getById(id);
    }

    /** POST /api/bookings */
    @PostMapping
    public ResponseEntity<BookingDto> create(@RequestBody BookingCreateDto dto) {
        BookingDto created = bookingService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** PUT /api/bookings/{id} */
    @PutMapping("/{id}")
    public BookingDto update(@PathVariable Long id, @RequestBody BookingCreateDto dto) {
        return bookingService.update(id, dto);
    }

    /** DELETE /api/bookings/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bookingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
