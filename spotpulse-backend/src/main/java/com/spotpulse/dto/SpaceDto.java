package com.spotpulse.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SpaceDto {
    private Long id;
    private String name;
    private String description;
    private String address;
    private String city;
    private String imageUrl;
    private List<RoomDto> rooms;
    private String contactEmail;
    private Double latitude;
    private Double longitude;

    public SpaceDto(Long id, String name, String description, String address, String city,
                    String imageUrl, List<RoomDto> rooms, String contactEmail) {
        this(id, name, description, address, city, imageUrl, rooms, contactEmail, null, null);
    }
}
