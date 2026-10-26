package com.spotpulse;

import com.spotpulse.model.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.repository.*;
import com.spotpulse.service.BookingAnalyticsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.h2.console.enabled=false"
})
@Transactional
class BookingAnalyticsTests {
    @Autowired SpaceRepository spaces;
    @Autowired RoomRepository rooms;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @Autowired BookingAnalyticsService analytics;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    User user;
    Room first;
    Room second;
    Room third;

    Space space(String name) {
        var space = new Space(); space.setName(name); space.setAddress("Public address"); space.setCity("Kyiv");
        return spaces.save(space);
    }

    Room room(Space space) {
        var room = new Room(); room.setName("Meeting room"); room.setCapacity(4);
        room.setHourlyRate(BigDecimal.TEN); room.setSpace(space);
        return rooms.save(room);
    }

    // Historical fixtures belong only in tests. The booking service's future-time validation is unchanged.
    void booking(Room room, String start, String end, BookingStatus status) {
        var booking = new Booking(); booking.setRoom(room); booking.setUser(user); booking.setStatus(status);
        booking.setStartTime(LocalDateTime.parse(start)); booking.setEndTime(LocalDateTime.parse(end));
        bookings.save(booking);
    }

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        user = new User(); user.setName("Private person"); user.setEmail("private@example.test"); users.save(user);
        var a = space("River Hub"); first = room(a); second = room(space("Garden Hub")); third = room(a);
        booking(first, "2026-09-30T23:00", "2026-10-01T01:00", BookingStatus.CONFIRMED);
        booking(first, "2026-10-01T00:00", "2026-10-01T01:00", BookingStatus.CONFIRMED);
        booking(first, "2026-10-01T23:30", "2026-10-03T01:00", BookingStatus.CONFIRMED);
        booking(first, "2026-10-02T10:00", "2026-10-02T11:00", BookingStatus.CANCELLED);
        booking(first, "2026-10-03T23:59:59", "2026-10-04T01:00", BookingStatus.CONFIRMED);
        booking(first, "2026-10-04T00:00", "2026-10-04T01:00", BookingStatus.CONFIRMED);
        booking(second, "2026-10-02T12:00", "2026-10-02T13:00", BookingStatus.CONFIRMED);
        booking(second, "2026-10-03T12:00", "2026-10-03T13:00", BookingStatus.CONFIRMED);
        booking(second, "2026-10-02T13:00", "2026-10-02T14:00", BookingStatus.CANCELLED);
        booking(third, "2026-10-03T15:00", "2026-10-03T16:00", BookingStatus.CONFIRMED);
        em.flush();
    }

    @Test
    void groupsByStartDateAndFiltersInclusiveBoundariesWithoutCountingSpanningDaysTwice() throws Exception {
        mvc.perform(get("/api/analytics/bookings").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grouping").value("BOOKING_START_DATE"))
                .andExpect(jsonPath("$.from").value("2026-10-01")).andExpect(jsonPath("$.to").value("2026-10-03"))
                .andExpect(jsonPath("$.total").value(8)).andExpect(jsonPath("$.statuses.confirmed").value(6))
                .andExpect(jsonPath("$.statuses.cancelled").value(2)).andExpect(jsonPath("$.daily.length()").value(3))
                .andExpect(jsonPath("$.daily[0].date").value("2026-10-01"))
                .andExpect(jsonPath("$.daily[0].confirmed").value(2)).andExpect(jsonPath("$.daily[0].cancelled").value(0))
                .andExpect(jsonPath("$.daily[1].confirmed").value(1)).andExpect(jsonPath("$.daily[1].cancelled").value(2))
                .andExpect(jsonPath("$.daily[2].confirmed").value(3));
    }

    @Test
    void ranksConfirmedBookingsAndKeepsDuplicateRoomNamesSeparateWithStableTies() throws Exception {
        mvc.perform(get("/api/analytics/bookings").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(jsonPath("$.popularRooms.length()").value(3))
                .andExpect(jsonPath("$.popularRooms[0].roomId").value(first.getId()))
                .andExpect(jsonPath("$.popularRooms[0].count").value(3))
                .andExpect(jsonPath("$.popularRooms[1].roomId").value(second.getId()))
                .andExpect(jsonPath("$.popularRooms[1].count").value(2))
                .andExpect(jsonPath("$.popularRooms[1].spaceName").value("Garden Hub"))
                .andExpect(jsonPath("$.popularRooms[2].roomId").value(third.getId()))
                .andExpect(jsonPath("$.popularRooms[2].count").value(1));
        mvc.perform(get("/api/analytics/bookings").param("from", "2026-10-03").param("to", "2026-10-03"))
                .andExpect(jsonPath("$.popularRooms[0].roomId").value(first.getId()))
                .andExpect(jsonPath("$.popularRooms[1].roomId").value(second.getId()))
                .andExpect(jsonPath("$.popularRooms[2].roomId").value(third.getId()));
    }

    @Test
    void returnsExplicitZeroDaysAndEmptyRoomRankingForEmptyData() throws Exception {
        mvc.perform(get("/api/analytics/bookings").param("from", "2026-09-01").param("to", "2026-09-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.statuses.confirmed").value(0)).andExpect(jsonPath("$.statuses.cancelled").value(0))
                .andExpect(jsonPath("$.daily.length()").value(2)).andExpect(jsonPath("$.daily[0].confirmed").value(0))
                .andExpect(jsonPath("$.daily[1].cancelled").value(0)).andExpect(jsonPath("$.popularRooms").isEmpty());
        mvc.perform(get("/api/analytics/bookings").param("from", "2024-01-01").param("to", "2024-12-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.daily.length()").value(366));
    }

    @Test
    void validatesMissingMalformedReversedAndExcessiveDateRanges() throws Exception {
        for (String[] range : new String[][]{{"2026-10-03", "2026-10-01"}, {"bad", "2026-10-01"},
                {"2026-02-30", "2026-03-01"}, {"2024-01-01", "2025-01-01"},
                {"0000-01-01", "0000-01-02"}, {"9999-12-31", "9999-12-31"}, {"", "2026-10-01"}}) {
            mvc.perform(get("/api/analytics/bookings").param("from", range[0]).param("to", range[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(get("/api/analytics/bookings")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics/bookings").param("from", "2026-10-01")).andExpect(status().isBadRequest());
    }

    @Test
    void capsPopularRoomsAtTenAndUsesOnlyTwoQueriesWithoutPersonalData() throws Exception {
        for (int i = 0; i < 11; i++) {
            booking(room(first.getSpace()), "2026-11-01T10:00", "2026-11-01T11:00", BookingStatus.CONFIRMED);
        }
        em.flush(); em.clear();
        var statistics = emf.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        var result = analytics.getBookings(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"));
        assertThat(result.popularRooms()).hasSize(10);
        assertThat(result.total()).isEqualTo(11);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        String json = mvc.perform(get("/api/analytics/bookings").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$..userId").isEmpty())
                .andExpect(jsonPath("$..email").isEmpty()).andExpect(jsonPath("$..startTime").isEmpty())
                .andExpect(jsonPath("$..endTime").isEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("Private person", "private@example.test", "userName", "bookingId");
    }
}
