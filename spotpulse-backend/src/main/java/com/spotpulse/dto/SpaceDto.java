package com.spotpulse.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
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
    private boolean premium;
    private boolean featured;
    private boolean premiumBadge;

    public SpaceDto(Long id, String name, String description, String address, String city,
                    String imageUrl, List<RoomDto> rooms, String contactEmail, Double latitude, Double longitude) {
        this.id = id; this.name = name; this.description = description; this.address = address; this.city = city;
        this.imageUrl = imageUrl; this.rooms = rooms; this.contactEmail = contactEmail;
        this.latitude = latitude; this.longitude = longitude;
    }

    public SpaceDto(Long id, String name, String description, String address, String city,
                    String imageUrl, List<RoomDto> rooms, String contactEmail) {
        this(id, name, description, address, city, imageUrl, rooms, contactEmail, null, null);
    }
}
