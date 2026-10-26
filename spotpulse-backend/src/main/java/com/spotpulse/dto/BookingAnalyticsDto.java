package com.spotpulse.dto;

import java.time.LocalDate;
import java.util.List;

/** Aggregate public demonstration data. No user or individual booking information. */
public record BookingAnalyticsDto(LocalDate from, LocalDate to, String grouping,
                                  long total, StatusCounts statuses, List<Day> daily,
                                  int popularRoomsLimit, List<PopularRoom> popularRooms) {
    public record StatusCounts(long confirmed, long cancelled) { }
    public record Day(LocalDate date, long confirmed, long cancelled) { }
    public record PopularRoom(Long roomId, String roomName, Long spaceId, String spaceName, long count) { }
}
