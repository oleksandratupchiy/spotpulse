package com.spotpulse.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreateDto {
    private String name;
    private Integer capacity;
    private BigDecimal hourlyRate;
    private Long spaceId;
}
