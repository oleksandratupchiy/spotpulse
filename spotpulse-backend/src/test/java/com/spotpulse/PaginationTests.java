package com.spotpulse;

import com.jayway.jsonpath.JsonPath;
import com.spotpulse.dto.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pagination;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.h2.console.enabled=false"
})
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class PaginationTests {
    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired UserService users;
    @Autowired BookingService bookings;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    List<Long> spaceIds;
    List<Long> roomIds;
    List<Long> userIds;
    List<Long> bookingIds;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .alwaysDo(result -> org.springframework.security.core.context.SecurityContextHolder.setContext(org.springframework.security.test.context.TestSecurityContextHolder.getContext()))
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("regression-admin").roles("ADMIN"))
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .build();
        spaceIds = new ArrayList<>();
        roomIds = new ArrayList<>();
        userIds = new ArrayList<>();
        bookingIds = new ArrayList<>();
        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        for (int i = 0; i < 12; i++) {
            spaceIds.add(spaces.create(new SpaceCreateDto("Shared + Hub", null, "Street %02d".formatted(i),
                    i < 8 ? "Kyiv" : "Lviv", null)).getId());
            userIds.add(users.create(new UserCreateDto("User", "user" + i + "@example.com", null)).getId());
            roomIds.add(rooms.create(new RoomCreateDto("Room", 4, BigDecimal.TEN,
                    spaceIds.get(i < 8 ? 0 : 8))).getId());
            bookingIds.add(bookings.create(new BookingCreateDto(roomIds.get(i < 8 ? 0 : 8),
                    userIds.get(i < 8 ? 0 : 8), start.plusHours(i), start.plusHours(i + 1),
                    i < 8 ? BookingStatus.CONFIRMED : BookingStatus.CANCELLED)).getId());
        }
    }

    List<Long> ids(String endpoint) {
        return switch (endpoint) {
            case "spaces" -> spaceIds;
            case "rooms" -> roomIds;
            case "users" -> userIds;
            case "bookings" -> bookingIds;
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    ResultActions offset(String endpoint, int skip, int limit, int length, long total, String next) throws Exception {
        return mvc.perform(get("/api/" + endpoint).param("skip", "" + skip).param("limit", "" + limit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(length))
                .andExpect(jsonPath("$.skip").value(skip))
                .andExpect(jsonPath("$.limit").value(limit))
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.nextLink").value(next == null ? nullValue() : org.hamcrest.Matchers.is(next)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spaces", "rooms", "users", "bookings"})
    void firstMiddleLastAndEmptyPages(String endpoint) throws Exception {
        offset(endpoint, 0, 5, 5, 12, "/api/" + endpoint + "?skip=5&limit=5")
                .andExpect(jsonPath("$.items[0].id").value(ids(endpoint).get(0)));
        offset(endpoint, 5, 5, 5, 12, "/api/" + endpoint + "?skip=10&limit=5")
                .andExpect(jsonPath("$.items[0].id").value(ids(endpoint).get(5)));
        offset(endpoint, 10, 5, 2, 12, null)
                .andExpect(jsonPath("$.items[1].id").value(ids(endpoint).get(11)));
        offset(endpoint, 12, 5, 0, 12, null);
        offset(endpoint, 20, 5, 0, 12, null);
        offset(endpoint, 7, 5, 5, 12, null); // Full final page with a non-aligned offset.
    }

    @ParameterizedTest
    @ValueSource(strings = {"spaces", "rooms", "users", "bookings"})
    void nonAlignedOffsetsUseExactDatabaseOffset(String endpoint) throws Exception {
        offset(endpoint, 3, 5, 5, 12, "/api/" + endpoint + "?skip=8&limit=5")
                .andExpect(jsonPath("$.items[0].id").value(ids(endpoint).get(3)))
                .andExpect(jsonPath("$.items[4].id").value(ids(endpoint).get(7)));
        offset(endpoint, 8, 5, 4, 12, null)
                .andExpect(jsonPath("$.items[0].id").value(ids(endpoint).get(8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spaces", "rooms", "users", "bookings"})
    void pageAndSizeKeepEstablishedResponse(String endpoint) throws Exception {
        mvc.perform(get("/api/" + endpoint).param("page", "1").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].id").value(ids(endpoint).get(5)))
                .andExpect(jsonPath("$.number").value(1)).andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.items").doesNotExist());
        mvc.perform(get("/api/" + endpoint)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spaces", "rooms", "users", "bookings"})
    void validatesParametersAndMaximumLimit(String endpoint) throws Exception {
        for (String[] invalid : List.of(new String[]{"skip", "-1"}, new String[]{"skip", "abc"},
                new String[]{"skip", ""}, new String[]{"skip", "1.5"}, new String[]{"skip", "2147483648"},
                new String[]{"limit", "0"}, new String[]{"limit", "-1"}, new String[]{"limit", "101"},
                new String[]{"limit", "abc"}, new String[]{"limit", ""}, new String[]{"limit", "2147483648"})) {
            mvc.perform(get("/api/" + endpoint).param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(get("/api/" + endpoint).param("skip", "0", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/" + endpoint).param("limit", "5", "6"))
                .andExpect(status().isBadRequest());
        offset(endpoint, 0, 100, 12, 12, null);
        offset(endpoint, Integer.MAX_VALUE, 5, 0, 12, null);
    }

    @ParameterizedTest
    @CsvSource({"spaces,city,Kyiv", "spaces,search,Street 0", "rooms,spaceId,SPACE",
            "bookings,userId,USER", "bookings,roomId,ROOM", "bookings,status,CONFIRMED"})
    void filtersPreserveMatchingTotalsAndLinks(String endpoint, String filter, String value) throws Exception {
        value = switch (value) {
            case "SPACE" -> spaceIds.getFirst().toString();
            case "USER" -> userIds.getFirst().toString();
            case "ROOM" -> roomIds.getFirst().toString();
            default -> value;
        };
        // Search matches Street 00..09; other filters match the first eight records.
        int total = filter.equals("search") ? 10 : 8;
        mvc.perform(get("/api/" + endpoint).param(filter, value)
                        .param("page", "1").param("size", "4").param("sort", "id,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(total))
                .andExpect(jsonPath("$.content[0].id").value(ids(endpoint).get(total - 5)));
        String body = mvc.perform(get("/api/" + endpoint).param(filter, value)
                        .param("skip", "3").param("limit", "4").param("sort", "id,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.items[0].id").value(ids(endpoint).get(total - 4)))
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(body, "$.nextLink");
        assertThat(link).contains("skip=7", "limit=4", "sort=id%2Cdesc", filter + "=");
        mvc.perform(get(URI.create(link))).andExpect(status().isOk())
                .andExpect(jsonPath("$.skip").value(7)).andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.items.length()").value(total - 7))
                .andExpect(jsonPath("$.items[0].id").value(ids(endpoint).get(total - 8)))
                .andExpect(jsonPath("$.nextLink").value(nullValue()));
    }

    @Test
    void emptyFilteredResults() throws Exception {
        mvc.perform(get("/api/spaces").param("city", "Missing").param("skip", "0").param("limit", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.nextLink").value(nullValue()));
    }

    @Test
    void bookingLinksKeepAllSuppliedFiltersAndExistingPrecedence() throws Exception {
        String body = mvc.perform(get("/api/bookings").param("skip", "0").param("limit", "5")
                        .param("userId", userIds.getFirst().toString())
                        .param("roomId", roomIds.getFirst().toString()).param("status", "CONFIRMED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(8))
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(body, "$.nextLink");
        assertThat(link).contains("userId=" + userIds.getFirst(), "roomId=" + roomIds.getFirst(),
                "status=CONFIRMED", "skip=5", "limit=5");
        mvc.perform(get(URI.create(link))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8)).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.nextLink").value(nullValue()));
    }

    @Test
    void repeatedSortAndEncodedFiltersSurviveFollowingLinkWithStableTies() throws Exception {
        String body = mvc.perform(get("/api/spaces").param("search", "Shared + Hub")
                        .param("city", "Kyiv & Lviv").param("sort", "name,asc", "address,desc")
                        .param("skip", "3").param("limit", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.items[0].id").value(spaceIds.get(8)))
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(body, "$.nextLink");
        assertThat(link).contains("Shared%20%2B%20Hub", "Kyiv%20%26%20Lviv", "sort=name%2Casc", "sort=address%2Cdesc");
        mvc.perform(get(URI.create(link))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12)).andExpect(jsonPath("$.items[0].id").value(spaceIds.get(3)));

        // Every name is identical: the appended unique ID must decide ties across pages.
        mvc.perform(get("/api/spaces").param("skip", "3").param("limit", "5").param("sort", "name,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(spaceIds.get(3)))
                .andExpect(jsonPath("$.items[4].id").value(spaceIds.get(7)));
    }

    @Test
    void defaultsAndMixedInterfacesAreExplicit() throws Exception {
        mvc.perform(get("/api/spaces").param("skip", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skip").value(3))
                .andExpect(jsonPath("$.limit").value(20));
        mvc.perform(get("/api/spaces").param("limit", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skip").value(0))
                .andExpect(jsonPath("$.limit").value(5));
        mvc.perform(get("/api/spaces").param("skip", "3").param("limit", "5")
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(spaceIds.get(3)))
                .andExpect(jsonPath("$.nextLink").value("/api/spaces?skip=8&limit=5"));
    }
}
