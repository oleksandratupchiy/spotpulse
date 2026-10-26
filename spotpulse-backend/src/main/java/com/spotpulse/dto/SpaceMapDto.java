package com.spotpulse.dto;

/** Public discovery data only; no rooms, contacts, users or bookings. */
public record SpaceMapDto(Long id, String name, String city, String address,
                          Double latitude, Double longitude) { }
