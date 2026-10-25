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
    @Positive
    private Integer capacity;
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal hourlyRate;
    @NotNull
    private Long spaceId;
}
