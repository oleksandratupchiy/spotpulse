package com.spotpulse.availability;

import com.spotpulse.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvailabilityLimitsTests {
    @Test void refusesTruncatedResultsInsteadOfClaimingAnIntervalIsAvailable() {
        var bookings = mock(BookingRepository.class); var rooms = mock(RoomRepository.class);
        when(rooms.existsById(3L)).thenReturn(true);
        var from = LocalDateTime.of(2026, 12, 1, 10, 0);
        when(bookings.occupied(eq(3L), eq(from), eq(from.plusDays(1)), any()))
                .thenReturn(Collections.nCopies(501, new AvailabilityService.Occupied(from, from.plusHours(1))));
        assertThatThrownBy(() -> new AvailabilityService(bookings, rooms).read(3L, from, from.plusDays(1)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(bookings).occupied(eq(3L), eq(from), eq(from.plusDays(1)), argThat(page -> page.getPageSize() == 501));
    }
    @Test void perAddressCapPayloadLimitsAndDisconnectCleanupAreEnforced() throws Exception {
        var hub = new AvailabilitySocket();
        try {
            var sessions = new ArrayList<WebSocketSession>();
            for (int i = 0; i <= 20; i++) {
                var session = mock(WebSocketSession.class); sessions.add(session);
                when(session.getId()).thenReturn("session-" + i); when(session.isOpen()).thenReturn(true);
                when(session.getAttributes()).thenReturn(Map.of("roomId", 3L));
                when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 1000 + i));
                hub.afterConnectionEstablished(session);
            }
            assertThat(hub.connections()).isEqualTo(20);
            verify(sessions.get(20)).close(CloseStatus.SERVICE_OVERLOAD);
            verify(sessions.getFirst()).setTextMessageSizeLimit(128);
            verify(sessions.getFirst()).setBinaryMessageSizeLimit(128);
            hub.afterConnectionClosed(sessions.getFirst(), CloseStatus.NORMAL);
            hub.afterConnectionEstablished(sessions.get(20));
            assertThat(hub.connections()).isEqualTo(20);
        } finally { hub.stop(); }
    }
}
