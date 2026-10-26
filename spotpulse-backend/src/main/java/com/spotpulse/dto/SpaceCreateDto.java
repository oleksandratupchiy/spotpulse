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

    @Email
    @Size(max = 254)
    private String contactEmail;

    @DecimalMin("-90")
    @DecimalMax("90")
    private Double latitude;

    @DecimalMin("-180")
    @DecimalMax("180")
    private Double longitude;

    @AssertTrue(message = "Latitude and longitude must be supplied together")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCoordinatePairValid() {
        return (latitude == null) == (longitude == null);
    }

    public SpaceCreateDto(String name, String description, String address, String city, String imageUrl, String contactEmail) {
        this(name, description, address, city, imageUrl, contactEmail, null, null);
    }

    // Preserve existing callers and REST requests that omit contact information.
    public SpaceCreateDto(String name, String description, String address, String city, String imageUrl) {
        this(name, description, address, city, imageUrl, null);
    }
}
