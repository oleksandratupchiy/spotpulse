package com.spotpulse.dto;

import com.spotpulse.model.enums.BookingStatus;
import lombok.*;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BookingCreateDto {
    @NotNull
    private Long roomId;
    private Long userId;
    @NotNull
    @FutureOrPresent
    private LocalDateTime startTime;
    @NotNull
    @FutureOrPresent
    private LocalDateTime endTime;
    private BookingStatus status;
    @AssertTrue(message = "End time must be after start time.")
    public boolean isTimeIntervalValid() {
        return startTime == null || endTime == null || startTime.isBefore(endTime);
    }

    @AssertTrue(message = "Bookings can last up to 12 hours.")
    public boolean isDurationValid() {
        return startTime == null || endTime == null
                || java.time.Duration.between(startTime, endTime).compareTo(java.time.Duration.ofHours(12)) <= 0;
    }
}
