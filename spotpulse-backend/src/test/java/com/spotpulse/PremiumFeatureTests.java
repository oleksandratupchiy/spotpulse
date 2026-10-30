package com.spotpulse;

import com.spotpulse.dto.*;
import com.spotpulse.model.*;
import com.spotpulse.model.enums.*;
import com.spotpulse.premium.*;
import com.spotpulse.repository.*;
import com.spotpulse.security.AppOidcUser;
import com.spotpulse.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed isolated fixtures: exercises actual cache hits and after-commit invalidation. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:b6-premium;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.h2.console.enabled=false"})
class PremiumFeatureTests {
    @Autowired FeatureFlags flags;
    @Autowired FeatureFlagRepository flagRepository;
    @Autowired FeatureFlagDefaults defaults;
    @Autowired SpaceService spaces;
    @Autowired SpaceRepository spaceRepository;
    @Autowired RoomService rooms;
    @Autowired BookingService bookings;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository users;
    @Autowired PremiumAnalytics analytics;
    @Autowired CacheManager caches;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    AppOidcUser admin, owner, other, member;
    Long normal, premium, foreign, room, emptyRoom;
    LocalDate from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 3);

    AppOidcUser actor(Role role) {
        var subject = UUID.randomUUID().toString();
        var local = new User(); local.setName(role.name()); local.setEmail(subject + "@example.test"); local.setRole(role);
        local.setOauthProvider("google"); local.setOauthSubject(subject); users.saveAndFlush(local);
        var oidc = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("test-token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", subject)));
        return new AppOidcUser(local, oidc);
    }
    OAuth2AuthenticationToken token(AppOidcUser user) { return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"); }
    void as(AppOidcUser user) { SecurityContextHolder.getContext().setAuthentication(token(user)); }
    Long space(String name, String city) { return spaces.create(new SpaceCreateDto(name, null, "Street 1", city, null, null)).getId(); }
    void enable() { as(admin); for (var key : FeatureKey.values()) flags.update(key.name(), true); spaces.setPremium(premium, true); }
    void historical(String start, BookingStatus status) {
        var b = new Booking(); b.setRoom(new Room()); b.getRoom().setId(room);
        b.setUser(users.findById(member.getUserId()).orElseThrow()); b.setStartTime(LocalDateTime.parse(start)); b.setEndTime(b.getStartTime().plusHours(1)); b.setStatus(status);
        bookingRepository.saveAndFlush(b);
    }
    String performance(Long id) { return "/api/analytics/spaces/" + id + "/room-performance?from=" + from + "&to=" + to; }

    @BeforeEach void seed() {
        admin = actor(Role.ADMIN); owner = actor(Role.OWNER); other = actor(Role.OWNER); member = actor(Role.USER);
        as(admin);
        // No test transaction: all writes below commit and trigger cache listeners.
        for (var key : FeatureKey.values()) flags.update(key.name(), false);
        for (var id : spaceRepository.findAll().stream().map(Space::getId).toList()) spaces.delete(id);
        as(owner); normal = space("Alpha Hub", "Kyiv"); premium = space("Zulu Hub", "Kyiv");
        room = rooms.create(new RoomCreateDto("Oak", 4, BigDecimal.TEN, premium)).getId();
        emptyRoom = rooms.create(new RoomCreateDto("Unused", 2, BigDecimal.ONE, premium)).getId();
        as(other); foreign = space("Other Hub", "Lviv");
        historical("2026-09-30T23:59", BookingStatus.CONFIRMED);
        historical("2026-10-01T00:00", BookingStatus.CONFIRMED);
        historical("2026-10-02T12:00", BookingStatus.CANCELLED);
        historical("2026-10-03T23:59", BookingStatus.CONFIRMED);
        historical("2026-10-04T00:00", BookingStatus.CONFIRMED);
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void defaultsAreExplicitPersistedAndFailClosed() {
        as(admin);
        assertThat(flags.list()).hasSize(4).allMatch(f -> !f.enabled() && !f.description().isBlank());
        assertThat(flagRepository.count()).isEqualTo(4);
        assertThat(flags.state()).isEqualTo(new FeatureFlags.State(false, false, false, false));
        assertThat(spaceRepository.findById(premium).orElseThrow().isPremium()).isFalse();
        flagRepository.deleteById(FeatureKey.PREMIUM_FEATURES_ENABLED);
        assertThat(flags.state().premium()).isFalse();
        defaults.run(null);
        assertThat(flagRepository.findById(FeatureKey.PREMIUM_FEATURES_ENABLED)).isPresent();
    }

    @Test void adminUpdatesPersistAndStartupDoesNotResetThem() throws Exception {
        mvc.perform(put("/api/admin/features/PREMIUM_FEATURES_ENABLED").with(authentication(token(admin))).with(csrf())
                .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(put("/api/admin/spaces/{id}/premium", premium).with(authentication(token(admin))).with(csrf())
                .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premium").value(true));
        defaults.run(null);
        assertThat(flagRepository.findById(FeatureKey.PREMIUM_FEATURES_ENABLED).orElseThrow().isEnabled()).isTrue();
        assertThat(spaceRepository.findById(premium).orElseThrow().isPremium()).isTrue();
        mvc.perform(get("/api/admin/features").with(authentication(token(admin)))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(put("/api/admin/spaces/{id}/premium", premium).with(authentication(token(admin))).with(csrf())
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premium").value(false));
    }

    @Test void usersAndOwnersCannotMutateGlobalFlagsOrEntitlementsEvenDirectly() throws Exception {
        for (var actor : List.of(owner, member)) {
            mvc.perform(put("/api/admin/features/PREMIUM_FEATURES_ENABLED").with(authentication(token(actor))).with(csrf())
                    .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/spaces/{id}/premium", premium).with(authentication(token(actor))).with(csrf())
                    .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
            as(actor);
            assertThatThrownBy(() -> flags.update("PREMIUM_FEATURES_ENABLED", true)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> spaces.setPremium(premium, true)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void csrfUnknownKeysAndMissingToggleAreRejected() throws Exception {
        mvc.perform(put("/api/admin/features/PREMIUM_FEATURES_ENABLED").with(authentication(token(admin)))
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/spaces/{id}/premium", premium).with(authentication(token(admin)))
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/features/SECURITY_DISABLED").with(authentication(token(admin))).with(csrf())
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/features/PREMIUM_FEATURES_ENABLED").with(authentication(token(admin))).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/features")).andExpect(status().isUnauthorized());
        mvc.perform(get(performance(premium))).andExpect(status().isUnauthorized());
    }

    @Test void ownerAndAdminGetActualRoomComparisonWithEmptyRoomsAndInclusiveBoundaries() throws Exception {
        enable();
        for (var actor : List.of(owner, admin)) {
            mvc.perform(get(performance(premium)).with(authentication(token(actor)))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.rooms.length()").value(2)).andExpect(jsonPath("$.rooms[0].roomId").value(room))
                    .andExpect(jsonPath("$.rooms[0].confirmed").value(2)).andExpect(jsonPath("$.rooms[0].cancelled").value(1))
                    .andExpect(jsonPath("$.rooms[1].confirmed").value(0)).andExpect(jsonPath("$.rooms[1].cancelled").value(0));
        }
        as(owner); assertThat(analytics.get(premium, from, to).rooms().getFirst().cancellationRate()).isCloseTo(100.0 / 3, within(0.001));
        mvc.perform(get("/api/management/spaces/{id}", premium).with(authentication(token(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premium").value(true));
    }

    @Test void foreignOwnersUsersAndUnentitledSpacesAreDeniedButStandardAnalyticsStillWorks() throws Exception {
        enable();
        for (var actor : List.of(other, member))
            mvc.perform(get(performance(premium)).with(authentication(token(actor)))).andExpect(status().isForbidden());
        mvc.perform(get(performance(normal)).with(authentication(token(owner)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/spaces/{id}", premium).with(authentication(token(other)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/analytics/bookings?from=2026-10-01&to=2026-10-03").with(authentication(token(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
    }

    @Test void warmPremiumCacheCannotBypassEitherFlagOrRevokedEntitlementOrChangedOwnership() throws Exception {
        enable();
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isOk());
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isOk());
        var cache = (CaffeineCache) caches.getCache("bookingAnalytics");
        assertThat(cache.getNativeCache().stats().hitCount()).isPositive();
        as(admin); flags.update("ADVANCED_ANALYTICS_ENABLED", false);
        assertThat(cache.getNativeCache().estimatedSize()).isZero();
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isConflict());
        as(admin); flags.update("ADVANCED_ANALYTICS_ENABLED", true); flags.update("PREMIUM_FEATURES_ENABLED", false);
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isConflict());
        as(admin); flags.update("PREMIUM_FEATURES_ENABLED", true); spaces.setPremium(premium, false);
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isForbidden());
        as(admin); spaces.setPremium(premium, true);
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isOk());
        as(admin); spaces.assignOwner(premium, other.getUserId());
        mvc.perform(get(performance(premium)).with(authentication(token(owner)))).andExpect(status().isForbidden());
        mvc.perform(get(performance(premium)).with(authentication(token(other)))).andExpect(status().isOk());
    }

    @Test void featuredOrderingPreservesFiltersSortTiesAndBothPaginationInterfaces() throws Exception {
        enable();
        mvc.perform(get("/spaces")).andExpect(status().isOk());
        mvc.perform(get("/api/spaces?skip=0&limit=1&sort=name,asc")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items[0].id").value(premium))
                .andExpect(jsonPath("$.items[0].featured").value(true)).andExpect(jsonPath("$.items[0].premiumBadge").value(true));
        mvc.perform(get("/api/spaces?skip=1&limit=2&sort=name,asc")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(normal)).andExpect(jsonPath("$.items[1].id").value(foreign));
        mvc.perform(get("/api/spaces?city=Kyiv&skip=0&limit=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].id").value(premium));
        mvc.perform(get("/api/spaces?search=Hub&skip=0&limit=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items[0].id").value(premium));
        mvc.perform(get("/api/spaces?page=1&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(normal));
        as(admin); flags.update("FEATURED_SPACES_ENABLED", false);
        mvc.perform(get("/api/spaces?skip=0&limit=3&sort=premium,desc")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(normal)).andExpect(jsonPath("$.items[1].id").value(premium))
                .andExpect(jsonPath("$.items[1].featured").value(false)).andExpect(jsonPath("$.items[1].premiumBadge").value(false));
    }

    @Test void publicDetailCacheReflectsFlagsAndEntitlementImmediatelyAfterCommit() throws Exception {
        enable();
        for (int i = 0; i < 2; i++) mvc.perform(get("/api/spaces/{id}", premium)).andExpect(status().isOk())
                .andExpect(jsonPath("$.premiumBadge").value(true));
        as(admin); flags.update("PREMIUM_BADGES_ENABLED", false);
        mvc.perform(get("/api/spaces/{id}", premium)).andExpect(status().isOk()).andExpect(jsonPath("$.premiumBadge").value(false));
        mvc.perform(get("/api/spaces?skip=0&limit=1")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(premium));
        as(admin); flags.update("PREMIUM_BADGES_ENABLED", true); spaces.setPremium(premium, false);
        mvc.perform(get("/api/spaces/{id}", premium)).andExpect(status().isOk()).andExpect(jsonPath("$.premium").value(false)).andExpect(jsonPath("$.premiumBadge").value(false));
        as(admin); spaces.setPremium(premium, true); flags.update("PREMIUM_FEATURES_ENABLED", false);
        mvc.perform(get("/api/spaces?skip=0&limit=1")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(normal));
    }

    @Test void bookingAndRoomMutationsInvalidatePremiumAggregates() throws Exception {
        enable(); as(owner); analytics.get(premium, from, to);
        rooms.update(emptyRoom, new RoomCreateDto("Renamed unused room", 2, BigDecimal.ONE, premium));
        assertThat(analytics.get(premium, from, to).rooms().get(1).roomName()).isEqualTo("Renamed unused room");
        LocalDate day = LocalDate.now().plusDays(3);
        as(member); var b = bookings.create(new BookingCreateDto(room, null, day.atTime(10, 0), day.atTime(11, 0), null));
        as(owner); assertThat(analytics.get(premium, day, day).rooms().getFirst().confirmed()).isEqualTo(1);
        as(member); bookings.cancel(b.getId());
        as(owner); assertThat(analytics.get(premium, day, day).rooms().getFirst().cancelled()).isEqualTo(1);
    }

    @Test void analyticsValidatesDateRangeAndLegacyWritesCannotSelfGrant() throws Exception {
        enable();
        for (String query : List.of("from=2026-10-03&to=2026-10-01", "from=2025-01-01&to=2026-10-01", "from=invalid&to=2026-10-01"))
            mvc.perform(get("/api/analytics/spaces/" + premium + "/room-performance?" + query).with(authentication(token(owner)))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/spaces/{id}", normal).with(authentication(token(owner))).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Alpha Hub\",\"address\":\"Street 1\",\"city\":\"Kyiv\",\"premium\":true,\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premium").value(false));
    }
}
