package com.spotpulse.premium;

import java.time.LocalDate;
import java.util.List;

public record RoomPerformanceDto(Long spaceId, LocalDate from, LocalDate to, String grouping, List<RoomPerformance> rooms) {
    public record RoomPerformance(Long roomId, String roomName, long confirmed, long cancelled, double cancellationRate) { }
}
