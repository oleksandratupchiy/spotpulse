package com.spotpulse.telegram;

import com.spotpulse.dto.BookingCreateDto;
import com.spotpulse.model.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.repository.*;
import com.spotpulse.service.BookingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:booking-notifications;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@WithMockUser(roles = "ADMIN")
@ExtendWith(OutputCaptureExtension.class)
class BookingNotificationTests {
    @Autowired BookingService bookings;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository users;
    @Autowired SpaceRepository spaces;
    @Autowired RoomRepository rooms;
    @Autowired TelegramAccountRepository accounts;
    @Autowired TelegramRuntime runtime;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean TelegramClient client;
    Long userId, roomId;
    final LocalDateTime start = LocalDateTime.now().plusDays(2).withNano(0);

    @BeforeEach void setup() {
        when(client.configured()).thenReturn(true);
        runtime.ready(123L, "spotpulse_test_bot");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var user = new User(); user.setName("Member"); user.setEmail(UUID.randomUUID() + "@booking.test");
            user = users.saveAndFlush(user); userId = user.getId();
            var space = new Space(); space.setName("Hub"); space.setCity("Kyiv"); space.setAddress("Main Street");
            space = spaces.saveAndFlush(space);
            var room = new Room(); room.setName("Room"); room.setSpace(space); room.setCapacity(2); room.setHourlyRate(BigDecimal.ZERO);
            roomId = rooms.saveAndFlush(room).getId();
            var account = new TelegramAccount(); account.setUser(user); account.setBotId(123L); account.setChatId(987654321L + userId);
            // Legacy/missing preferences must default to enabled.
            accounts.saveAndFlush(account);
        });
    }
    @AfterEach void stop() { runtime.unavailable(); }
    BookingCreateDto input(BookingStatus status) {
        return new BookingCreateDto(roomId, userId, start, start.plusHours(1), status);
    }
    void preference(Boolean enabled) {
        var account = accounts.findById(userId).orElseThrow();
        account.setBookingNotificationsEnabled(enabled); accounts.saveAndFlush(account);
    }

    @Test void confirmationAndCancellationReachLinkedUserOnlyAfterCommit(CapturedOutput output) {
        var tx = new TransactionTemplate(transactionManager);
        var saved = tx.execute(status -> {
            var booking = bookings.create(input(null));
            verify(client, never()).send(anyLong(), anyString());
            return booking;
        });
        String timeRange = start.format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", java.util.Locale.ENGLISH))
                + " – " + start.plusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", java.util.Locale.ENGLISH));
        verify(client).send(eq(987654321L + userId), eq("Your booking for Room is confirmed.\nHub\n" + timeRange + "\nTimes as booked · Confirmed"));
        clearInvocations(client);
        tx.executeWithoutResult(status -> {
            bookings.cancel(saved.getId());
            verify(client, never()).send(anyLong(), anyString());
        });
        verify(client).send(eq(987654321L + userId), eq("Your booking for Room was cancelled.\nHub\n" + timeRange + "\nTimes as booked · Cancelled"));
        assertThat(bookingRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        bookings.cancel(saved.getId());
        verify(client, times(1)).send(anyLong(), anyString());
        assertThat(output).contains("event_id=", "booking_id=" + saved.getId(), "event_type=CONFIRMED outcome=sent",
                "event_type=CANCELLED outcome=sent").doesNotContain(String.valueOf(987654321L + userId));
    }

    @Test void rolledBackCreationAndCancellationNeverDeliver() {
        var tx = new TransactionTemplate(transactionManager);
        Long rolledBack = tx.execute(status -> {
            var saved = bookings.create(input(null)); status.setRollbackOnly(); return saved.getId();
        });
        assertThat(bookingRepository.existsById(rolledBack)).isFalse();
        verify(client, never()).send(anyLong(), anyString());
        var saved = bookings.create(input(null));
        clearInvocations(client);
        tx.executeWithoutResult(status -> { bookings.cancel(saved.getId()); status.setRollbackOnly(); });
        assertThat(bookingRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(client, never()).send(anyLong(), anyString());
    }

    @Test void explicitOptOutSkipsBothEventsAndCanBeReenabled(CapturedOutput output) {
        preference(false);
        var saved = bookings.create(input(null)); bookings.cancel(saved.getId());
        verify(client, never()).send(anyLong(), anyString());
        assertThat(output).contains("outcome=skipped category=preference_disabled");
        preference(true);
        bookings.create(input(null));
        verify(client).send(anyLong(), contains("confirmed"));
    }

    @Test void telegramFailureCannotRollbackBookingAndLogsOnlySafeCategory(CapturedOutput output) {
        doThrow(new TelegramClient.Failure(403, 5)).when(client).send(anyLong(), anyString());
        var saved = bookings.create(input(null));
        assertThat(bookingRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        bookings.cancel(saved.getId());
        assertThat(bookingRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(client, times(2)).send(anyLong(), anyString());
        assertThat(output).contains("outcome=failed category=api_forbidden")
                .doesNotContain(String.valueOf(987654321L + userId), "https://api.telegram.org", "SpotPulse booking #");
    }

    @Test void unexpectedSenderExceptionIsSanitized(CapturedOutput output) {
        doThrow(new IllegalStateException("SECRET token URL chat message")).when(client).send(anyLong(), anyString());
        var saved = bookings.create(input(null));
        assertThat(bookingRepository.existsById(saved.getId())).isTrue();
        assertThat(output).contains("outcome=failed category=internal_error").doesNotContain("SECRET token URL chat message");
    }

    @Test void unlinkedAndWrongBotAccountsAreSkipped(CapturedOutput output) {
        var account = accounts.findById(userId).orElseThrow(); account.setChatId(null); accounts.saveAndFlush(account);
        var saved = bookings.create(input(null));
        account.setChatId(987654321L + userId); account.setBotId(999L); accounts.saveAndFlush(account);
        bookings.cancel(saved.getId());
        verify(client, never()).send(anyLong(), anyString());
        assertThat(output).contains("category=not_linked", "category=bot_mismatch");
    }

    @Test void adminStatusTransitionsDeliverButUnchangedUpdatesDoNot() {
        var saved = bookings.create(input(BookingStatus.CANCELLED));
        verify(client, never()).send(anyLong(), anyString());
        bookings.update(saved.getId(), input(BookingStatus.CONFIRMED));
        verify(client).send(anyLong(), contains("confirmed"));
        bookings.update(saved.getId(), input(BookingStatus.CONFIRMED));
        verify(client, times(1)).send(anyLong(), anyString());
        bookings.update(saved.getId(), input(BookingStatus.CANCELLED));
        verify(client).send(anyLong(), contains("cancelled"));
    }
}
