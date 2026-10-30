package com.spotpulse.availability;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:c10-integration;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
class AvailabilityIntegrationTests extends AvailabilityTestSupport {
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void seed() { fixture(); }
    @AfterEach void clear() throws Exception { cleanup(); }

    @Test void publicSnapshotIsFreshClippedAndContainsNoPrivateBookingFields() throws Exception {
        assertThat(snapshot(room).body()).contains("\"occupied\":[]");
        var saved = create();
        var response = snapshot(room);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("\"start\"").doesNotContain("bookingId", "userId", "userName", "email", "Private customer");
        assertThat(availability.read(room, from.plusHours(2).plusMinutes(15), from.plusHours(2).plusMinutes(45)).occupied())
                .containsExactly(new AvailabilityService.Occupied(from.plusHours(2).plusMinutes(15), from.plusHours(2).plusMinutes(45)));
        assertThat(availability.read(room, from.plusHours(3), to).occupied()).isEmpty();
        assertThat(availability.read(room, from, from.plusHours(2)).occupied()).isEmpty();
        bookings.cancel(saved.getId());
        assertThat(snapshot(room).body()).contains("\"occupied\":[]");
    }
    @Test void validatesIntervalsAndMissingRooms() {
        for (var end : java.util.List.of(from, from.minusMinutes(1), from.plusDays(32)))
            assertThatThrownBy(() -> availability.read(room, from, end)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(availability.read(room, from, from.plusDays(31)).occupied()).isEmpty();
        assertThatThrownBy(() -> availability.read(Long.MAX_VALUE, from, to)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
    @Test void realSocketsNotifyMultipleRelevantSubscribersAfterCreateAndCancel() throws Exception {
        var one = connect(room); var two = connect(room);
        var otherRoom = rooms.create(new com.spotpulse.dto.RoomCreateDto("Other", 2, java.math.BigDecimal.ONE, spaces.create(new com.spotpulse.dto.SpaceCreateDto("Other Hub", "", "Street", "Kyiv", null)).getId())).getId();
        var unrelated = connect(otherRoom);
        var saved = create();
        for (var client : java.util.List.of(one, two)) {
            assertThat(client.take().json()).contains("availability.changed", "\"roomId\":" + room, "\"signal\"")
                    .doesNotContain("bookingId", "userId", "startTime", "email", "Private customer");
        }
        assertThat(unrelated.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        bookings.cancel(saved.getId());
        assertThat(one.take().json()).contains("availability.changed");
        assertThat(two.take().json()).contains("availability.changed");
        assertThat(snapshot(room).body()).contains("\"occupied\":[]");
        bookings.cancel(saved.getId()); // idempotent cancellation sends no second signal
        assertThat(one.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }
    @Test void rollbackAndOverlapConflictNeverPublish() throws Exception {
        var socket = connect(room);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { create(); tx.setRollbackOnly(); });
        assertThat(socket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
        assertThat(snapshot(room).body()).contains("\"occupied\":[]");
        create(); socket.take();
        assertThatThrownBy(this::create).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(socket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
    }
    @Test void originIsMandatoryAndUntrustedOriginsAreDenied() {
        for (String origin : new String[]{null, "https://evil.example", "http://localhost:5173.evil.example"})
            assertThatThrownBy(() -> connect(room, origin)).hasCauseInstanceOf(WebSocketHandshakeException.class);
    }
    @Test void socketCannotExecuteMutationsAndDisconnectedClientsAreRemoved() throws Exception {
        int before = hub.connections();
        var client = connect(room);
        client.socket.sendText("{\"command\":\"book\",\"roomId\":" + room + "}", true).join();
        assertThat(client.closed.get(15, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(snapshot(room).body()).contains("\"occupied\":[]");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (hub.connections() > before && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(hub.connections()).isLessThanOrEqualTo(before);
        var denied = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bookings"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403); // CSRF still protects all HTTP booking writes
    }
    @Test void adminMovesAndDeletesInvalidateBothAffectedRooms() throws Exception {
        Long other = rooms.create(new com.spotpulse.dto.RoomCreateDto("Moved room", 2, java.math.BigDecimal.ONE,
                spaces.create(new com.spotpulse.dto.SpaceCreateDto("Moved Hub", "", "Street", "Kyiv", null)).getId())).getId();
        var original = connect(room); var target = connect(other);
        var saved = create(); original.take();
        bookings.update(saved.getId(), new com.spotpulse.dto.BookingCreateDto(other, null, from.plusHours(2), from.plusHours(3), null));
        assertThat(original.take().json()).contains("availability.changed");
        assertThat(target.take().json()).contains("availability.changed");
        assertThat(snapshot(room).body()).contains("\"occupied\":[]");
        assertThat(snapshot(other).body()).contains("\"start\"");
        bookings.delete(saved.getId()); target.take();
        assertThat(snapshot(other).body()).contains("\"occupied\":[]");
    }
    @Test void failedDeliveryCannotUndoABookingOrPreventOtherSubscribersFromUpdating() throws Exception {
        var healthy = connect(room);
        var failed = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(failed.getId()).thenReturn("broken-test-session");
        org.mockito.Mockito.when(failed.isOpen()).thenReturn(true);
        org.mockito.Mockito.when(failed.getAttributes()).thenReturn(java.util.Map.of("roomId", room));
        hub.afterConnectionEstablished(failed);
        org.mockito.Mockito.doThrow(new java.io.IOException("test transport failure")).when(failed).sendMessage(org.mockito.ArgumentMatchers.any());
        assertThat(create().getStatus()).isEqualTo(com.spotpulse.model.enums.BookingStatus.CONFIRMED);
        assertThat(healthy.take().json()).contains("availability.changed");
        assertThat(snapshot(room).body()).contains("\"start\"");
    }
}
