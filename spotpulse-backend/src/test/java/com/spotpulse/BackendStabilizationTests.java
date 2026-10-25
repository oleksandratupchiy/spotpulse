package com.spotpulse;

import com.spotpulse.controller.ApiExceptionHandler;
import com.spotpulse.controller.UserController;
import com.spotpulse.dto.*;
import com.spotpulse.model.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.repository.*;
import com.spotpulse.service.*;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPOTPULSE_TEST_DATABASE_URL:jdbc:h2:mem:stabilization;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}",
        "spring.datasource.driver-class-name=${SPOTPULSE_TEST_DATABASE_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${SPOTPULSE_TEST_DATABASE_USER:sa}",
        "spring.datasource.password=${SPOTPULSE_TEST_DATABASE_PASSWORD:}",
        "spring.jpa.database-platform=${SPOTPULSE_TEST_DATABASE_DIALECT:org.hibernate.dialect.H2Dialect}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.h2.console.enabled=false"
})
class BackendStabilizationTests {
    @Autowired BookingService bookings;
    @Autowired UserService users;
    @Autowired RoomService rooms;
    @Autowired SpaceService spaces;
    @Autowired BookingRepository bookingRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired SpaceRepository spaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    Long userId;
    Long roomId;
    Long otherRoomId;
    LocalDateTime start;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        roomRepository.deleteAll();
        spaceRepository.deleteAll();
        userRepository.deleteAll();
        userId = users.create(new UserCreateDto("Alex", "alex@example.com", null)).getId();
        Long spaceId = spaces.create(new SpaceCreateDto("Hub", null, "Main Street", "Kyiv", null)).getId();
        roomId = rooms.create(new RoomCreateDto("Room A", 4, BigDecimal.ZERO, spaceId)).getId();
        otherRoomId = rooms.create(new RoomCreateDto("Room B", 8, new BigDecimal("10.00"), spaceId)).getId();
        start = LocalDateTime.now().plusDays(1).withNano(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    BookingCreateDto booking(Long room, LocalDateTime from, LocalDateTime to, BookingStatus status) {
        return new BookingCreateDto(room, userId, from, to, status);
    }

    BookingCreateDto booking() {
        return booking(roomId, start, start.plusHours(1), null);
    }

    void assertStatus(Runnable operation, int expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(expected));
    }

    @Test
    void successfulBookingPreservesDefaultStatusAndResponseFields() {
        BookingDto saved = bookings.create(booking());
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getRoomName()).isEqualTo("Room A");
        assertThat(saved.getUserName()).isEqualTo("Alex");
    }

    @Test
    void rejectsEveryKindOfOverlapForSameRoom() {
        bookings.create(booking());
        for (BookingCreateDto candidate : List.of(
                booking(),
                booking(roomId, start.minusMinutes(10), start.plusMinutes(10), null),
                booking(roomId, start.plusMinutes(50), start.plusHours(2), null),
                booking(roomId, start.plusMinutes(10), start.plusMinutes(50), null),
                booking(roomId, start.minusHours(1), start.plusHours(2), null))) {
            assertStatus(() -> bookings.create(candidate), 409);
        }
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    void allowsAdjacentIntervalsOnBothSides() {
        bookings.create(booking());
        bookings.create(booking(roomId, start.minusHours(1), start, null));
        bookings.create(booking(roomId, start.plusHours(1), start.plusHours(2), null));
        assertThat(bookingRepository.count()).isEqualTo(3);
    }

    @Test
    void allowsSameIntervalForDifferentRooms() {
        bookings.create(booking());
        bookings.create(booking(otherRoomId, start, start.plusHours(1), null));
        assertThat(bookingRepository.count()).isEqualTo(2);
    }

    @Test
    void cancelledBookingsDoNotBlockAndCanOverlapConfirmedBookings() {
        bookings.create(booking(roomId, start, start.plusHours(1), BookingStatus.CANCELLED));
        bookings.create(booking());
        bookings.create(booking(roomId, start, start.plusHours(1), BookingStatus.CANCELLED));
        assertThat(bookingRepository.count()).isEqualTo(3);
    }

    @Test
    void rejectsEqualReversedPastAndMissingTimestamps() {
        for (BookingCreateDto invalid : List.of(
                booking(roomId, start, start, null),
                booking(roomId, start.plusHours(1), start, null),
                booking(roomId, start.minusDays(2), start, null),
                booking(roomId, null, start, null),
                booking(roomId, start, null, null))) {
            assertThatThrownBy(() -> bookings.create(invalid)).isInstanceOf(ConstraintViolationException.class);
        }
        assertThat(bookingRepository.count()).isZero();
    }

    @Test
    void updatesExcludeSelfAndRejectConflictsWithoutChangingExistingBooking() {
        BookingDto first = bookings.create(booking());
        assertThat(bookings.update(first.getId(), booking()).getId()).isEqualTo(first.getId());
        BookingDto second = bookings.create(booking(roomId, start.plusHours(2), start.plusHours(3), null));
        assertStatus(() -> bookings.update(second.getId(), booking()), 409);
        assertThat(bookings.getById(second.getId()).getStartTime()).isEqualTo(start.plusHours(2));
        assertThatThrownBy(() -> bookings.update(second.getId(), booking(roomId, start, start, null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void movingRoomsAndReconfirmingCancelledBookingsCheckAvailability() {
        bookings.create(booking());
        BookingDto other = bookings.create(booking(otherRoomId, start, start.plusHours(1), null));
        assertStatus(() -> bookings.update(other.getId(), booking()), 409);
        BookingDto cancelled = bookings.create(booking(roomId, start, start.plusHours(1), BookingStatus.CANCELLED));
        assertStatus(() -> bookings.update(cancelled.getId(), booking(roomId, start, start.plusHours(1), BookingStatus.CONFIRMED)), 409);
        assertThat(bookings.update(cancelled.getId(), booking()).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancellingBookingFreesRoom() {
        BookingDto first = bookings.create(booking());
        bookings.update(first.getId(), booking(roomId, start, start.plusHours(1), BookingStatus.CANCELLED));
        bookings.create(booking());
        assertThat(bookingRepository.count()).isEqualTo(2);
    }

    @Test
    void missingUsersRoomsAndBookingsReturnNotFoundOnCreateAndUpdate() {
        BookingDto saved = bookings.create(booking());
        BookingCreateDto missingRoom = booking(Long.MAX_VALUE, start, start.plusHours(1), null);
        BookingCreateDto missingUser = booking();
        missingUser.setUserId(Long.MAX_VALUE);
        for (BookingCreateDto dto : List.of(missingRoom, missingUser)) {
            assertStatus(() -> bookings.create(dto), 404);
            assertStatus(() -> bookings.update(saved.getId(), dto), 404);
        }
        assertStatus(() -> bookings.update(Long.MAX_VALUE, booking()), 404);
    }

    @Test
    void userEmailIsUniqueAndUnchangedEmailCanBeUpdated() {
        UserCreateDto unchanged = new UserCreateDto("Alex Updated", "alex@example.com", null);
        assertThat(users.update(userId, unchanged).getName()).isEqualTo("Alex Updated");
        assertStatus(() -> users.create(unchanged), 409);
        Long secondId = users.create(new UserCreateDto("Other", "other@example.com", null)).getId();
        assertStatus(() -> users.update(secondId, unchanged), 409);
        assertThat(users.getById(secondId).getEmail()).isEqualTo("other@example.com");
        assertThat(users.update(secondId, new UserCreateDto("Other", "new@example.com", null)).getEmail())
                .isEqualTo("new@example.com");
    }

    @Test
    void databaseStillEnforcesUniqueEmail() {
        User duplicate = new User();
        duplicate.setName("Duplicate");
        duplicate.setEmail("alex@example.com");
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentBookingsHaveExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> attempt = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    bookings.create(booking());
                    return 201;
                } catch (ResponseStatusException ex) {
                    return ex.getStatusCode().value();
                }
            };
            Future<Integer> a = executor.submit(attempt);
            Future<Integer> b = executor.submit(attempt);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                go.countDown();
            }
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    String bookingJson(Long room, Long user, LocalDateTime from, LocalDateTime to) {
        return "{\"roomId\":%d,\"userId\":%d,\"startTime\":\"%s\",\"endTime\":\"%s\"}"
                .formatted(room, user, from, to);
    }

    @Test
    void apiPreservesSuccessAndReturnsProblemDetailsFor400404409() throws Exception {
        String json = bookingJson(roomId, userId, start, start.plusHours(1));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andExpect(jsonPath("roomName").value("Room A"))
                .andExpect(jsonPath("status").value("CONFIRMED"));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("status").value(409));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("errors").isArray());
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(roomId, userId, start, start)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(roomId, Long.MAX_VALUE, start, start.plusHours(1))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("status").value(404));
        mvc.perform(get("/api/bookings/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("detail").value("Booking not found"));
    }

    @Test
    void apiValidatesAllRequestDtosAndMalformedInput() throws Exception {
        for (String path : List.of("users", "rooms", "spaces", "bookings")) {
            mvc.perform(post("/api/" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
        }
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alex\",\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Room\",\"capacity\":0,\"hourlyRate\":-1,\"spaceId\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Invalid request input"));
        mvc.perform(get("/api/bookings/not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Invalid request input"));
    }

    @Test
    void apiEmailConflictsReturn409OnCreateAndUpdate() throws Exception {
        String duplicate = "{\"name\":\"Another\",\"email\":\"alex@example.com\"}";
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict()).andExpect(jsonPath("status").value(409));
        Long second = users.create(new UserCreateDto("Other", "other@example.com", null)).getId();
        mvc.perform(put("/api/users/{id}", second).contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/users/{id}", userId).contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value("alex@example.com"));
    }

    @Test
    void unexpectedErrorsDoNotExposeInternalDetails() throws Exception {
        UserService failingService = mock(UserService.class);
        when(failingService.getById(1L)).thenThrow(new IllegalStateException("secret database credentials"));
        MockMvc isolated = MockMvcBuilders.standaloneSetup(new UserController(failingService))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        isolated.perform(get("/api/users/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("exception").doesNotExist()).andExpect(jsonPath("trace").doesNotExist());
    }

    @Test
    void mvcFormsRenderErrorsAndPreserveCreateEditListAndDelete() throws Exception {
        long before = spaceRepository.count();
        mvc.perform(post("/spaces").param("name", " ").param("address", "").param("city", ""))
                .andExpect(status().isOk()).andExpect(view().name("spaces/form"))
                .andExpect(model().attributeHasFieldErrors("space", "name", "address", "city"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("alert-danger")));
        assertThat(spaceRepository.count()).isEqualTo(before);
        mvc.perform(post("/spaces").param("name", "New Hub").param("address", "Street").param("city", "Lviv"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/spaces"));
        Long id = spaceRepository.findAll().stream().filter(s -> s.getName().equals("New Hub")).findFirst().orElseThrow().getId();
        mvc.perform(get("/spaces/{id}/edit", id)).andExpect(status().isOk()).andExpect(view().name("spaces/form"));
        mvc.perform(post("/spaces/{id}", id).param("name", "").param("address", "Street").param("city", "Kyiv"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("space", "name"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/spaces/" + id)));
        assertThat(spaces.getById(id).getName()).isEqualTo("New Hub");
        mvc.perform(post("/spaces/{id}", id).param("name", "Updated Hub").param("address", "Street").param("city", "Kyiv"))
                .andExpect(status().is3xxRedirection());
        assertThat(spaces.getById(id).getName()).isEqualTo("Updated Hub");
        mvc.perform(get("/spaces")).andExpect(status().isOk()).andExpect(view().name("spaces/list"));
        mvc.perform(post("/spaces/{id}/delete", id)).andExpect(status().is3xxRedirection());
        assertThat(spaceRepository.existsById(id)).isFalse();
    }
}
