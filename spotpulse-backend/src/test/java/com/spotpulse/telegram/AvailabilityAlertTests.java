package com.spotpulse.telegram;

import com.spotpulse.availability.*;
import com.spotpulse.dto.BookingCreateDto;
import com.spotpulse.model.*;
import com.spotpulse.model.enums.*;
import com.spotpulse.repository.*;
import com.spotpulse.security.AppOidcUser;
import com.spotpulse.service.BookingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPOTPULSE_TEST_DATABASE_URL:jdbc:h2:mem:availability-alerts;DB_CLOSE_DELAY=-1}",
        "spring.datasource.driver-class-name=${SPOTPULSE_TEST_DATABASE_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${SPOTPULSE_TEST_DATABASE_USER:sa}",
        "spring.datasource.password=${SPOTPULSE_TEST_DATABASE_PASSWORD:}",
        "spring.jpa.database-platform=${SPOTPULSE_TEST_DATABASE_DIALECT:org.hibernate.dialect.H2Dialect}",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spotpulse.seo.public-base-url=https://example.test"})
class AvailabilityAlertTests {
    @Autowired AvailabilityAlertService alerts;
    @Autowired AvailabilityAlertRepository repository;
    @Autowired AvailabilityAlertStore store;
    @Autowired TelegramNotificationEligibility eligibility;
    @Autowired TelegramRuntime runtime;
    @Autowired TelegramAccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired SpaceRepository spaces;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingService bookings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @MockitoBean TelegramClient client;
    AppOidcUser watcher, other, booker;
    Long roomId;
    final LocalDateTime start = LocalDate.now().plusDays(10).atTime(10, 0);
    final LocalDateTime end = start.plusHours(2);
    AvailabilityAlertDispatcher dispatcher;
    MockMvc mvc;

    @BeforeEach void seed() {
        watcher = actor(Role.USER); other = actor(Role.USER); booker = actor(Role.ADMIN);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var space = new Space(); space.setName("Quiet Hub\nCity"); space.setCity("Kyiv"); space.setAddress("Main Street");
            space.setOwner(users.findById(booker.getUserId()).orElseThrow()); spaces.saveAndFlush(space);
            var room = new Room(); room.setName("Blue room"); room.setSpace(space); room.setCapacity(2); room.setHourlyRate(BigDecimal.TEN);
            roomId = rooms.saveAndFlush(room).getId();
            var account = new TelegramAccount(); account.setUser(users.findById(watcher.getUserId()).orElseThrow());
            account.setBotId(123L); account.setChatId(777L + watcher.getUserId()); accounts.saveAndFlush(account);
        });
        when(client.configured()).thenReturn(true); runtime.ready(123L, "synthetic_test_bot");
        dispatcher = new AvailabilityAlertDispatcher(store, eligibility, client);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        as(watcher);
    }
    @AfterEach void stop() { dispatcher.stop(); runtime.unavailable(); SecurityContextHolder.clearContext(); }
    AppOidcUser actor(Role role) {
        var user = new User(); String key = UUID.randomUUID().toString();
        user.setName("Private subscriber"); user.setEmail(key + "@example.test"); user.setRole(role);
        user.setOauthProvider("google"); user.setOauthSubject(key); users.saveAndFlush(user);
        return new AppOidcUser(user, new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("synthetic", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", key))));
    }
    OAuth2AuthenticationToken auth(AppOidcUser actor) { return new OAuth2AuthenticationToken(actor, actor.getAuthorities(), "google"); }
    void as(AppOidcUser actor) { SecurityContextHolder.getContext().setAuthentication(auth(actor)); }
    Long occupy(LocalDateTime from, LocalDateTime to) {
        as(booker); var booking = bookings.create(new BookingCreateDto(roomId, null, from, to, null)); as(watcher); return booking.getId();
    }
    AvailabilityAlertService.View subscribe() { return alerts.create(new AvailabilityAlertService.Input(roomId, start, end)); }
    void release(Long booking) { as(booker); bookings.cancel(booking); as(watcher); }
    void due(Long id) { new TransactionTemplate(transactions).executeWithoutResult(tx -> repository.findById(id).orElseThrow().setNextAttemptAt(Instant.EPOCH)); }
    AvailabilityAlert row(Long id) { return repository.findById(id).orElseThrow(); }

    @Test void optionalListFiltersPreserveOwnershipOrderAndPageTotals() {
        var secondRoom = new Room(); secondRoom.setName("Second room"); secondRoom.setSpace(rooms.findById(roomId).orElseThrow().getSpace());
        secondRoom.setCapacity(2); secondRoom.setHourlyRate(BigDecimal.TEN); rooms.saveAndFlush(secondRoom);
        var own = List.of(
                listAlert(watcher, roomId, start, end, AvailabilityAlert.Status.ACTIVE),
                listAlert(watcher, roomId, start.plusHours(1), end, AvailabilityAlert.Status.READY),
                listAlert(watcher, roomId, start, end.plusHours(1), AvailabilityAlert.Status.SENT),
                listAlert(watcher, secondRoom.getId(), start, end, AvailabilityAlert.Status.CANCELLED),
                listAlert(watcher, roomId, start.minusDays(20), end.minusDays(20), AvailabilityAlert.Status.ACTIVE));
        listAlert(other, roomId, start, end, AvailabilityAlert.Status.ACTIVE);
        for (int mask = 0; mask < 8; mask++) {
            Long roomFilter = (mask & 1) == 0 ? null : roomId;
            LocalDateTime startFilter = (mask & 2) == 0 ? null : start;
            LocalDateTime endFilter = (mask & 4) == 0 ? null : end;
            for (boolean activeOnly : List.of(false, true)) {
                var expected = own.stream()
                        .filter(a -> roomFilter == null || a.getRoom().getId().equals(roomFilter))
                        .filter(a -> startFilter == null || a.getStartTime().equals(startFilter))
                        .filter(a -> endFilter == null || a.getEndTime().equals(endFilter))
                        .filter(a -> !activeOnly || a.active() && a.getStartTime().isAfter(LocalDateTime.now()))
                        .sorted(Comparator.comparing(AvailabilityAlert::getCreatedAt).thenComparing(AvailabilityAlert::getId).reversed())
                        .map(AvailabilityAlert::getId).toList();
                // Size one forces the count query, including when optional timestamps are null.
                for (int page = 0; page <= expected.size(); page++) {
                    var result = alerts.list(roomFilter, startFilter, endFilter, activeOnly, PageRequest.of(page, 1));
                    assertThat(result.getTotalElements()).isEqualTo(expected.size());
                    assertThat(result.getContent()).extracting(AvailabilityAlertService.View::id)
                            .containsExactlyElementsOf(expected.subList(Math.min(page, expected.size()), Math.min(page + 1, expected.size())));
                }
            }
        }
        assertThat(alerts.list(-1L, null, null, false, PageRequest.of(0, 1))).isEmpty();
    }

    @Test void listEndpointPreservesOffsetPaginationAndIntervalFilters() throws Exception {
        var first = listAlert(watcher, roomId, start, end, AvailabilityAlert.Status.ACTIVE);
        var second = listAlert(watcher, roomId, start, end, AvailabilityAlert.Status.SENT);
        listAlert(other, roomId, start, end, AvailabilityAlert.Status.ACTIVE);
        mvc.perform(get("/api/availability-alerts").with(authentication(auth(watcher)))
                .param("skip", "1").param("limit", "1").param("roomId", roomId.toString())
                .param("startTime", start.toString()).param("endTime", end.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.getId()));
        mvc.perform(get("/api/availability-alerts").with(authentication(auth(watcher)))
                .param("page", "0").param("size", "1").param("activeOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()));
        assertThat(second.getId()).isGreaterThan(first.getId());
    }

    AvailabilityAlert listAlert(AppOidcUser actor, Long room, LocalDateTime from, LocalDateTime to, AvailabilityAlert.Status status) {
        var alert = new AvailabilityAlert(); alert.setUser(users.findById(actor.getUserId()).orElseThrow());
        alert.setRoom(rooms.findById(room).orElseThrow()); alert.setStartTime(from); alert.setEndTime(to);
        // Identical creation times also verify the descending ID tiebreaker.
        alert.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        // List-only fixtures must not enter another test's explicit dispatcher scan.
        alert.setNextAttemptAt(Instant.now().plusSeconds(86400));
        if (status == AvailabilityAlert.Status.ACTIVE || status == AvailabilityAlert.Status.READY) alert.setStatus(status);
        else alert.finish(status);
        return repository.saveAndFlush(alert);
    }

    @Test void committedSubscriptionRestoresAcrossFreshReadsAndAuthenticationAndCanBeCancelled() {
        occupy(start, end); var saved = subscribe();
        SecurityContextHolder.clearContext(); as(watcher);
        var restored = alerts.list(roomId, start, end, true, PageRequest.of(0, 10)).getContent();
        assertThat(restored).extracting(AvailabilityAlertService.View::id).containsExactly(saved.id());
        assertThat(row(saved.id()).getCreatedAt()).isNotNull();
        alerts.cancel(saved.id()); alerts.cancel(saved.id());
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.CANCELLED);
        assertThat(alerts.list(roomId, start, end, true, PageRequest.of(0, 10))).isEmpty();
        assertThat(subscribe().id()).isNotEqualTo(saved.id());
    }
    @Test void concurrentDuplicateRequestsReturnOnePersistentActiveSubscription() throws Exception {
        occupy(start, end);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Long> create = () -> { as(watcher); gate.await(); try { return subscribe().id(); } finally { SecurityContextHolder.clearContext(); } };
            var one = pool.submit(create); var two = pool.submit(create); gate.countDown();
            assertThat(one.get(30, TimeUnit.SECONDS)).isEqualTo(two.get(30, TimeUnit.SECONDS));
        }
        assertThat(alerts.list(roomId, start, end, true, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }
    @Test void databaseConstraintAlsoRejectsDuplicateActiveRows() {
        occupy(start, end); var saved = subscribe();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var copy = new AvailabilityAlert(); copy.setUser(users.findById(watcher.getUserId()).orElseThrow());
            copy.setRoom(rooms.findById(roomId).orElseThrow()); copy.setStartTime(start); copy.setEndTime(end);
            repository.saveAndFlush(copy);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(subscribe().id()).isEqualTo(saved.id());
    }
    @Test void validatesFutureIncreasingTwelveHourIntervalAndExistingRoom() {
        for (var input : List.of(new AvailabilityAlertService.Input(roomId, start, start),
                new AvailabilityAlertService.Input(roomId, start, start.minusHours(1)),
                new AvailabilityAlertService.Input(roomId, start, start.plusHours(12).plusSeconds(1)),
                new AvailabilityAlertService.Input(roomId, start.minusDays(20), end.minusDays(20)),
                new AvailabilityAlertService.Input(roomId, null, end)))
            assertThatThrownBy(() -> alerts.create(input)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> alerts.create(new AvailabilityAlertService.Input(Long.MAX_VALUE, start, end)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(this::subscribe).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        occupy(start, end);
        assertThat(alerts.create(new AvailabilityAlertService.Input(roomId, start, start.plusHours(12))).id()).isNotNull();
    }
    @Test void endpointsRequireAuthenticationCsrfAndEnforceOwnershipWithoutClientUserIds() throws Exception {
        occupy(start, end); var saved = subscribe();
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/availability-alerts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/availability-alerts").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/availability-alerts").with(authentication(auth(watcher))).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/availability-alerts?skip=0&limit=10").with(authentication(auth(other))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(delete("/api/availability-alerts/" + saved.id()).with(authentication(auth(other))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/availability-alerts").with(authentication(auth(watcher))).with(csrf())
                .contentType("application/json").content("{\"roomId\":" + roomId + ",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\",\"userId\":" + other.getUserId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(saved.id()))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(row(saved.id()).getUser().getId()).isEqualTo(watcher.getUserId());
    }
    @Test void cancellationSchedulesOnlyAfterCommitAndDeliversOnceWithoutBrowser() {
        var booking = occupy(start, end); var saved = subscribe();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> repository.findById(saved.id()).orElseThrow().setNextAttemptAt(Instant.now().plusSeconds(3600)));
        as(booker);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            bookings.cancel(booking);
            assertThat(row(saved.id()).getNextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
            verify(client, never()).send(anyLong(), anyString());
        });
        assertThat(store.due()).contains(saved.id());
        dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
        assertThat(row(saved.id()).getNotifiedAt()).isNotNull();
        verify(client).send(eq(777L + watcher.getUserId()), argThat(text -> text.contains("Blue room") && text.contains("Quiet Hub City")
                && text.contains("Kyiv") && text.contains("https://example.test/rooms/" + roomId)
                && text.contains("not a reservation") && !text.contains("Private subscriber")));
        store.wake(roomId); store.wake(roomId); dispatcher.process(); bookings.cancel(booking); dispatcher.process();
        verify(client, times(1)).send(anyLong(), anyString());
    }
    @Test void rollbackNeverWakesAndAnotherOverlappingBookingPreventsNotification() {
        var first = occupy(start, start.plusHours(1)); var second = occupy(start.plusHours(1), end);
        var saved = subscribe();
        as(booker);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { bookings.cancel(first); tx.setRollbackOnly(); });
        assertThat(bookingRepository.findById(first).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        release(first); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.ACTIVE);
        verify(client, never()).send(anyLong(), anyString());
        release(second); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
    }
    @Test void unavailableTelegramLeavesRecoverablePendingDeliveryAndDoesNotRollbackCancellation() {
        var booking = occupy(start, end); var saved = subscribe(); runtime.unavailable();
        release(booking); dispatcher.process();
        assertThat(bookingRepository.findById(booking).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.READY);
        assertThat(row(saved.id()).getDeliveryReason()).isEqualTo("bot_unavailable");
        assertThat(row(saved.id()).getNotifiedAt()).isNull(); verify(client, never()).send(anyLong(), anyString());
        runtime.ready(123L, "synthetic_test_bot"); due(saved.id()); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
    }
    @Test void unlinkedOrOptedOutUserCanSubscribeButIsNeverFalselyMarkedSent() {
        var booking = occupy(start, end); as(other); var unlinked = subscribe(); as(watcher); var saved = subscribe();
        var account = accounts.findById(watcher.getUserId()).orElseThrow(); account.setBookingNotificationsEnabled(false); accounts.saveAndFlush(account);
        release(booking); dispatcher.process();
        assertThat(row(unlinked.id()).getDeliveryReason()).isEqualTo("not_linked");
        assertThat(row(saved.id()).getDeliveryReason()).isEqualTo("preference_disabled");
        verify(client, never()).send(anyLong(), anyString());
        account.setBookingNotificationsEnabled(true); accounts.saveAndFlush(account); due(saved.id()); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
    }
    @Test void deliveryFailureRetriesAndOnlyAcknowledgedSuccessBecomesSent() {
        var booking = occupy(start, end); var saved = subscribe(); release(booking);
        doThrow(new TelegramClient.Failure(429, 17)).doNothing().when(client).send(anyLong(), anyString());
        dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.READY);
        assertThat(row(saved.id()).getDeliveryReason()).isEqualTo("rate_limited");
        assertThat(row(saved.id()).getNextAttemptAt()).isAfter(Instant.now().plusSeconds(10));
        assertThat(row(saved.id()).getNotifiedAt()).isNull();
        dispatcher.process(); verify(client, times(1)).send(anyLong(), anyString());
        due(saved.id()); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
        verify(client, times(2)).send(anyLong(), anyString());
    }
    @Test void claimsAreExclusiveRecoverAfterLeaseExpiryAndRecheckNewOccupancy() throws Exception {
        var booking = occupy(start, end); var saved = subscribe(); release(booking);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> store.claim(saved.id())); var two = pool.submit(() -> store.claim(saved.id()));
            assertThat(Arrays.asList(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS))).filteredOn(Objects::nonNull).hasSize(1);
        }
        due(saved.id()); assertThat(store.claim(saved.id())).isNotNull();
        occupy(start, end); due(saved.id()); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.ACTIVE);
        verify(client, never()).send(anyLong(), anyString());
    }
    @Test void missedWakeRecoversFromDatabaseAndExpiredOrCancelledSubscriptionsNeverDeliver() {
        var booking = occupy(start, end); var saved = subscribe();
        // Simulate commit followed by process death before the after-commit event listener.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> bookingRepository.findById(booking).orElseThrow().setStatus(BookingStatus.CANCELLED));
        due(saved.id()); dispatcher.process(); assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
        occupy(start, end); var expired = subscribe();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> repository.findById(expired.id()).orElseThrow().setStartTime(LocalDateTime.now().minusMinutes(1)));
        dispatcher.process(); assertThat(row(expired.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.EXPIRED);
        verify(client, times(1)).send(anyLong(), anyString());
    }
    @Test void concurrentCancellationsAndDispatchersDeliverOneAlertForTheWholeInterval() throws Exception {
        var first = occupy(start, start.plusHours(1)); var second = occupy(start.plusHours(1), end); var saved = subscribe();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { as(booker); try { bookings.cancel(first); } finally { SecurityContextHolder.clearContext(); } });
            var two = pool.submit(() -> { as(booker); try { bookings.cancel(second); } finally { SecurityContextHolder.clearContext(); } });
            one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
            var dispatchOne = pool.submit(dispatcher::process); var dispatchTwo = pool.submit(dispatcher::process);
            dispatchOne.get(30, TimeUnit.SECONDS); dispatchTwo.get(30, TimeUnit.SECONDS);
        }
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.SENT);
        verify(client, times(1)).send(anyLong(), anyString());
    }
    @Test void cancelledSubscriptionNeverSchedulesDelivery() {
        var booking = occupy(start, end); var saved = subscribe(); alerts.cancel(saved.id()); release(booking);
        store.wake(roomId); dispatcher.process();
        assertThat(row(saved.id()).getStatus()).isEqualTo(AvailabilityAlert.Status.CANCELLED);
        assertThat(row(saved.id()).getNotifiedAt()).isNull(); verify(client, never()).send(anyLong(), anyString());
    }
}
