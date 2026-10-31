package com.spotpulse.dto;

import lombok.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreateDto {
    @NotBlank
    @Size(max = 255)
    private String name;
    @NotNull
    @Positive(message = "A room must have space for at least 1 person.")
    @Max(value = 15, message = "A room can hold up to 15 people.")
    private Integer capacity;
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal hourlyRate;
    @NotNull
    private Long spaceId;
}
