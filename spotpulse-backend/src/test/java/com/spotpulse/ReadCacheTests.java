package com.spotpulse;

import com.github.benmanes.caffeine.cache.Ticker;
import com.spotpulse.cache.*;
import com.spotpulse.dto.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.repository.*;
import com.spotpulse.service.*;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:read-caches;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.h2.console.enabled=false",
        "spotpulse.cache.ttl=10s", "spotpulse.cache.maximum-size=3"
})
@Import(ReadCacheTests.TimeConfiguration.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class ReadCacheTests {
    static class ManualTicker implements Ticker {
        final AtomicLong nanos = new AtomicLong();
        public long read() { return nanos.get(); }
        void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary ManualTicker manualTicker() { return new ManualTicker(); }
    }

    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired BookingService bookings;
    @Autowired UserService users;
    @Autowired SpaceRepository spaceRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    @Autowired BookingAnalyticsService analytics;
    @Autowired CacheManager manager;
    @Autowired ReadCaches revisions;
    @Autowired ManualTicker ticker;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory emf;
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;
    TransactionTemplate tx;
    MockMvc mvc;
    Long spaceId, otherSpaceId, roomId, userId, bookingId;
    LocalDateTime start;

    CaffeineCache cache(String name) { return (CaffeineCache) manager.getCache(name); }
    long hits(String name) { return cache(name).getNativeCache().stats().hitCount(); }
    long queries() { return emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount(); }
    SpaceCreateDto space(String name, String city) {
        return new SpaceCreateDto(name, "Description", "Public street", city, null, null, 50.45, 30.52);
    }
    RoomCreateDto room(String name, Long parent) { return new RoomCreateDto(name, 4, BigDecimal.TEN, parent); }
    BookingCreateDto booking(LocalDateTime time, BookingStatus status) {
        return new BookingCreateDto(roomId, userId, time, time.plusHours(1), status);
    }
    BookingAnalyticsDto totals() { return analytics.getBookings(start.toLocalDate(), start.toLocalDate()); }

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactions);
        // Isolated test database; direct cleanup is followed by clearing every cache.
        tx.executeWithoutResult(status -> {
            bookingRepository.deleteAll(); roomRepository.deleteAll(); userRepository.deleteAll(); spaceRepository.deleteAll();
        });
        ReadCaches.NAMES.forEach(name -> manager.getCache(name).clear());
        spaceId = spaces.create(space("Alpha Hub", "Kyiv")).getId();
        otherSpaceId = spaces.create(space("Beta Hub", "Lviv")).getId();
        roomId = rooms.create(room("Oak", spaceId)).getId();
        userId = users.create(new UserCreateDto("Private person", "private-cache@example.test", null)).getId();
        start = LocalDate.now().plusDays(3).atTime(10, 0);
        bookingId = bookings.create(booking(start, BookingStatus.CONFIRMED)).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .alwaysDo(result -> org.springframework.security.core.context.SecurityContextHolder.setContext(org.springframework.security.test.context.TestSecurityContextHolder.getContext()))
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("regression-admin").roles("ADMIN"))
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .build();
    }

    @Test
    void identicalPublicReadsHitWithoutSqlAndReturnIndependentDtoCopies() {
        var first = spaces.getById(spaceId);
        rooms.getById(roomId); spaces.autocomplete("Alpha"); spaces.mapData(null, null, 200); totals();
        long before = queries();
        first.setName("Caller mutation"); first.getRooms().getFirst().setName("Mutated nested room");
        assertThat(spaces.getById(spaceId).getName()).isEqualTo("Alpha Hub");
        assertThat(spaces.getById(spaceId).getRooms().getFirst().getName()).isEqualTo("Oak");
        var mutable = rooms.getById(roomId); mutable.setSpaceName("Caller mutation");
        assertThat(rooms.getById(roomId).getSpaceName()).isEqualTo("Alpha Hub");
        assertThat(spaces.autocomplete("Alpha")).hasSize(1);
        assertThat(spaces.mapData(null, null, 200).total()).isEqualTo(2);
        assertThat(totals().total()).isEqualTo(1);
        assertThat(queries()).isEqualTo(before);
        assertThatThrownBy(() -> totals().daily().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void idsFiltersLimitsAndDatesProduceDistinctKeys() {
        assertThat(spaces.getById(spaceId).getName()).isEqualTo("Alpha Hub");
        assertThat(spaces.getById(otherSpaceId).getName()).isEqualTo("Beta Hub");
        assertThat(spaces.autocomplete("Alpha").getFirst().id()).isEqualTo(spaceId);
        assertThat(spaces.autocomplete("Beta").getFirst().id()).isEqualTo(otherSpaceId);
        assertThat(spaces.mapData("Kyiv", null, 200).total()).isEqualTo(1);
        assertThat(spaces.mapData("Lviv", null, 200).items().getFirst().id()).isEqualTo(otherSpaceId);
        assertThat(spaces.mapData(null, "Alpha", 200).items().getFirst().id()).isEqualTo(spaceId);
        assertThat(spaces.mapData(null, null, 1).items()).hasSize(1);
        assertThat(spaces.mapData(null, null, 200).items()).hasSize(2);
        assertThat(totals().total()).isEqualTo(1);
        assertThat(analytics.getBookings(start.toLocalDate().plusDays(1), start.toLocalDate().plusDays(1)).total()).isZero();
        assertThat(analytics.getBookings(start.toLocalDate(), start.toLocalDate().plusDays(1)).daily()).hasSize(2);
    }

    @Test
    void ttlExpiresDeterministicallyAndEveryRegionHasSizeBound() {
        spaces.getById(spaceId); long before = queries();
        spaces.getById(spaceId); assertThat(queries()).isEqualTo(before);
        ticker.advance(Duration.ofSeconds(11));
        spaces.getById(spaceId); assertThat(queries()).isGreaterThan(before);
        for (String name : ReadCaches.NAMES) {
            // Use each region's real public DTO value; cleanup drains Caffeine's maintenance queue.
            Object value = name.equals("roomDetails") ? rooms.getById(roomId) :
                    name.equals("autocomplete") ? spaces.autocomplete("Alpha") :
                    name.equals("spaceMap") ? spaces.mapData(null, null, 200) :
                    name.equals("bookingAnalytics") ? totals() : spaces.getById(spaceId);
            for (int i = 0; i < 20; i++) cache(name).put("bound-test-" + i, value);
            cache(name).getNativeCache().cleanUp();
            assertThat(cache(name).getNativeCache().estimatedSize()).isLessThanOrEqualTo(3);
            assertThat(cache(name).getNativeCache().stats().evictionCount()).isPositive();
        }
    }

    @Test
    void spaceMutationsInvalidateMapAutocompleteRoomLabelsAnalyticsAndLandingDetails() throws Exception {
        spaces.getById(spaceId); rooms.getById(roomId); totals(); spaces.autocomplete("Alpha"); spaces.mapData(null, null, 200);
        spaces.update(spaceId, space("Renamed Hub", "Kyiv"));
        assertThat(spaces.autocomplete("Alpha")).isEmpty();
        assertThat(spaces.mapData(null, "Renamed", 200).items().getFirst().name()).isEqualTo("Renamed Hub");
        assertThat(rooms.getById(roomId).getSpaceName()).isEqualTo("Renamed Hub");
        assertThat(totals().popularRooms().getFirst().spaceName()).isEqualTo("Renamed Hub");
        mvc.perform(get("/spaces/{id}", spaceId)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Renamed Hub")));
        spaces.create(space("New Hub", "Kyiv"));
        assertThat(spaces.mapData(null, null, 200).total()).isEqualTo(3);
        spaces.delete(spaceId);
        assertThat(spaces.mapData(null, null, 200).total()).isEqualTo(2);
        assertThat(totals().total()).isZero(); // cascading room and booking deletion
        assertThatThrownBy(() -> spaces.getById(spaceId)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void roomCreateMoveRenameAndDeleteInvalidateNestedSpacesAndAnalytics() {
        spaces.getById(spaceId); totals(); rooms.getById(roomId);
        Long added = rooms.create(room("New room", spaceId)).getId();
        assertThat(spaces.getById(spaceId).getRooms()).hasSize(2);
        spaces.getById(otherSpaceId);
        rooms.update(roomId, room("Birch", otherSpaceId));
        assertThat(spaces.getById(spaceId).getRooms()).extracting(RoomDto::getId).containsExactly(added);
        assertThat(spaces.getById(otherSpaceId).getRooms()).extracting(RoomDto::getName).containsExactly("Birch");
        assertThat(rooms.getById(roomId).getSpaceName()).isEqualTo("Beta Hub");
        assertThat(totals().popularRooms().getFirst().roomName()).isEqualTo("Birch");
        assertThat(totals().popularRooms().getFirst().spaceName()).isEqualTo("Beta Hub");
        rooms.delete(roomId);
        assertThat(totals().total()).isZero();
        assertThat(spaces.getById(otherSpaceId).getRooms()).isEmpty();
    }

    @Test
    void bookingCreateCancelRescheduleDeleteAndUserCascadeInvalidateAnalytics() {
        assertThat(totals().statuses().confirmed()).isEqualTo(1);
        Long second = bookings.create(booking(start.plusHours(2), BookingStatus.CONFIRMED)).getId();
        assertThat(totals().statuses().confirmed()).isEqualTo(2);
        bookings.update(second, booking(start.plusHours(2), BookingStatus.CANCELLED));
        assertThat(totals().statuses().cancelled()).isEqualTo(1);
        assertThat(totals().popularRooms().getFirst().count()).isEqualTo(1);
        bookings.update(second, booking(start.plusDays(1), BookingStatus.CANCELLED));
        assertThat(totals().statuses().cancelled()).isZero();
        assertThat(analytics.getBookings(start.toLocalDate().plusDays(1), start.toLocalDate().plusDays(1)).total()).isEqualTo(1);
        bookings.delete(second);
        assertThat(analytics.getBookings(start.toLocalDate().plusDays(1), start.toLocalDate().plusDays(1)).total()).isZero();
        users.delete(userId);
        assertThat(totals().total()).isZero();
    }

    @Test
    void rollbackAndFailedWritesPreserveWarmCacheAndDoNotShareUncommittedReads() {
        spaces.getById(spaceId); totals();
        var key = revisions.key("spaceDetails", spaceId);
        tx.executeWithoutResult(status -> {
            spaces.update(spaceId, space("Uncommitted", "Kyiv"));
            assertThat(revisions.key("spaceDetails", spaceId)).isEqualTo(key);
            assertThat(spaces.getById(spaceId).getName()).isEqualTo("Uncommitted");
            status.setRollbackOnly();
        });
        assertThat(revisions.key("spaceDetails", spaceId)).isEqualTo(key);
        long before = queries();
        assertThat(spaces.getById(spaceId).getName()).isEqualTo("Alpha Hub");
        assertThat(queries()).isEqualTo(before);
        var analyticsKey = revisions.key("bookingAnalytics", start.toLocalDate(), start.toLocalDate());
        assertThatThrownBy(() -> bookings.create(booking(start, BookingStatus.CONFIRMED)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(revisions.key("bookingAnalytics", start.toLocalDate(), start.toLocalDate())).isEqualTo(analyticsKey);
        assertThat(totals().total()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            bookings.create(booking(start.plusHours(3), BookingStatus.CONFIRMED));
            assertThat(totals().total()).isEqualTo(2); // own uncommitted data bypasses shared cache
            status.setRollbackOnly();
        });
        assertThat(totals().total()).isEqualTo(1);
        assertThat(revisions.key("bookingAnalytics", start.toLocalDate(), start.toLocalDate())).isEqualTo(analyticsKey);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            spaces.update(spaceId, space("Must roll back", "Kyiv"));
            users.create(new UserCreateDto("Duplicate", "private-cache@example.test", null));
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(spaces.getById(spaceId).getName()).isEqualTo("Alpha Hub");
    }

    @Test
    void failureDuringCommitDoesNotInvalidateOrPublishUncommittedValues() {
        spaces.getById(spaceId);
        var key = revisions.key("spaceDetails", spaceId);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            spaces.update(spaceId, space("Commit must fail", "Kyiv"));
            // Fail at Hibernate's commit-time flush, after the service published its event.
            em.find(com.spotpulse.model.User.class, userId).setEmail(null);
        })).isInstanceOf(RuntimeException.class);
        assertThat(revisions.key("spaceDetails", spaceId)).isEqualTo(key);
        long before = queries();
        assertThat(spaces.getById(spaceId).getName()).isEqualTo("Alpha Hub");
        assertThat(queries()).isEqualTo(before);
    }

    @Test
    void lateOldGenerationPopulationCannotMakeCommittedDataStale() {
        var old = spaces.getById(spaceId);
        var oldKey = revisions.key("spaceDetails", spaceId);
        spaces.update(spaceId, space("Committed", "Kyiv"));
        cache("spaceDetails").put(oldKey, old); // deterministic simulation of an older read finishing late
        assertThat(revisions.key("spaceDetails", spaceId)).isNotEqualTo(oldKey);
        assertThat(spaces.getById(spaceId).getName()).isEqualTo("Committed");
    }

    @Test
    void errorsAreNotCachedAndApiResponsesPaginationAndSortingStayCompatible() throws Exception {
        long before = queries();
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> spaces.getById(Long.MAX_VALUE))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(queries() - before).isEqualTo(2);
        String first = mvc.perform(get("/api/spaces/{id}", spaceId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/api/spaces/{id}", spaceId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(hits("spaceDetails")).isPositive();
        mvc.perform(get("/api/spaces/map").param("limit", "0")).andExpect(status().isBadRequest());
        assertThat(spaces.getAll(PageRequest.of(0, 1, Sort.by("name"))).getContent().getFirst().getName()).isEqualTo("Alpha Hub");
        assertThat(spaces.getAll(PageRequest.of(1, 1, Sort.by("name"))).getContent().getFirst().getName()).isEqualTo("Beta Hub");
        assertThat(spaces.getAll(PageRequest.of(0, 1, Sort.by("name").descending())).getContent().getFirst().getName()).isEqualTo("Beta Hub");
        mvc.perform(get("/api/spaces").param("skip", "1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.total").value(2));
        assertThat(manager.getCacheNames()).doesNotContain("users", "bookings");
        assertThat(new ReadCaches(manager, new ReadCacheProperties(false, Duration.ofMinutes(1), 3)).allowed()).isFalse();
    }
}
