package com.spotpulse.cache;

/** Published within a write transaction; consumed only after its successful commit. */
public record ReadDataChanged(Kind kind) {
    public enum Kind { SPACES, ROOMS, BOOKINGS }
}
