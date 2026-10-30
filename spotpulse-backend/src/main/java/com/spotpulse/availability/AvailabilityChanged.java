package com.spotpulse.availability;

/** Non-sensitive invalidation event, published inside the booking transaction. */
public record AvailabilityChanged(Long roomId) { }
