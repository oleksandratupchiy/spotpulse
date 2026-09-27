package com.spotpulse.dto;

import lombok.*;

/**
 * Request DTO для створення/оновлення Space.
 * Не містить id та rooms — вони керуються сервером.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SpaceCreateDto {
    private String name;
    private String description;
    private String address;
    private String city;
    private String imageUrl;
}
