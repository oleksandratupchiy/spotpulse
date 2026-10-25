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
    @NotNull
    private Long userId;
    @NotNull
    @FutureOrPresent
    private LocalDateTime startTime;
    @NotNull
    @FutureOrPresent
    private LocalDateTime endTime;
    private BookingStatus status;
    @AssertTrue(message = "startTime must be before endTime")
    public boolean isTimeIntervalValid() {
        return startTime == null || endTime == null || startTime.isBefore(endTime);
    }
}
