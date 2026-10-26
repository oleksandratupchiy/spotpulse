package com.spotpulse.dto;

/** Public discovery fields only; no rooms, contact details, users or bookings. */
public record SpaceSuggestionDto(Long id, String name, String city, String address) {
}
