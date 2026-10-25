package com.spotpulse.dto;

import lombok.*;
import jakarta.validation.constraints.*;

/**
 * Request DTO для створення/оновлення Space.
 * Не містить id та rooms — вони керуються сервером.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SpaceCreateDto {
    @NotBlank
    @Size(max = 255)
    private String name;
    @Size(max = 2000)
    private String description;
    @NotBlank
    @Size(max = 255)
    private String address;
    @NotBlank
    @Size(max = 255)
    private String city;
    @Size(max = 255)
    private String imageUrl;
}
