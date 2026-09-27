package com.spotpulse.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomDto {
    private Long id;
    private String name;
    private Integer capacity;
    private BigDecimal hourlyRate;
    private Long spaceId;
    private String spaceName;
}
