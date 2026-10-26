package com.spotpulse;

import com.spotpulse.dto.*;
import com.spotpulse.model.User;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.UserRepository;
import com.spotpulse.security.AppOidcUser;
import com.spotpulse.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No wrapping test transaction: these calls actually populate shared Caffeine entries. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:security-cache;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.h2.console.enabled=false"})
class SecurityCacheTests {
    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired BookingService bookings;
    @Autowired BookingAnalyticsService analytics;
    @Autowired CacheManager manager;
    @Autowired WebApplicationContext context;

    AppOidcUser actor(String subject, Role role) {
        User local = new User(); local.setName(subject); local.setEmail(subject + "@example.test"); local.setRole(role);
        local.setOauthProvider("google"); local.setOauthSubject(subject); users.saveAndFlush(local);
        var oidc = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("qa-cache-token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", subject)));
        return new AppOidcUser(local, oidc);
    }
    OAuth2AuthenticationToken token(AppOidcUser user) { return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"); }
    void as(AppOidcUser user) { SecurityContextHolder.getContext().setAuthentication(token(user)); }

    @Test void cachedAnalyticsNeverCrossOwnershipOrBypassRoleRevocationAndOwnershipInvalidation() throws Exception {
        var admin = actor("admin", Role.ADMIN); var first = actor("first", Role.OWNER); var second = actor("second", Role.OWNER); var member = actor("member", Role.USER);
        try {
            as(first); Long a = spaces.create(new SpaceCreateDto("First Hub", null, "Street", "Kyiv", null)).getId();
            Long roomA = rooms.create(new RoomCreateDto("Oak", 4, BigDecimal.TEN, a)).getId();
            as(second); Long b = spaces.create(new SpaceCreateDto("Second Hub", null, "Street", "Lviv", null)).getId();
            Long roomB = rooms.create(new RoomCreateDto("Birch", 4, BigDecimal.TEN, b)).getId();
            var start = LocalDate.now().plusDays(2).atTime(10, 0); var date = start.toLocalDate();
            as(admin);
            bookings.create(new BookingCreateDto(roomA, member.getUserId(), start, start.plusHours(1), null));
            bookings.create(new BookingCreateDto(roomB, member.getUserId(), start, start.plusHours(1), null));
            bookings.create(new BookingCreateDto(roomB, member.getUserId(), start.plusHours(2), start.plusHours(3), null));
            assertThat(analytics.getBookings(date, date).total()).isEqualTo(3);
            as(first); assertThat(analytics.getBookings(date, date).total()).isEqualTo(1);
            var cache = (CaffeineCache) manager.getCache("bookingAnalytics");
            long hits = cache.getNativeCache().stats().hitCount();
            assertThat(analytics.getBookings(date, date).total()).isEqualTo(1);
            assertThat(cache.getNativeCache().stats().hitCount()).isEqualTo(hits + 1);
            as(member);
            assertThatThrownBy(() -> analytics.getBookings(date, date)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            as(second); assertThat(analytics.getBookings(date, date).total()).isEqualTo(2);
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            SecurityContextHolder.clearContext();
            String path = "/api/analytics/bookings?from=" + date + "&to=" + date;
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(authentication(token(member)))).andExpect(status().isForbidden());
            as(admin); spaces.assignOwner(a, second.getUserId());
            as(first); assertThat(analytics.getBookings(date, date).total()).isZero();
            as(second); assertThat(analytics.getBookings(date, date).total()).isEqualTo(3);
            as(admin); userService.update(second.getUserId(), new UserCreateDto("second", "second@example.test", Role.USER));
            SecurityContextHolder.clearContext();
            mvc.perform(get(path).with(authentication(token(second)))).andExpect(status().isForbidden());
        } finally { SecurityContextHolder.clearContext(); }
    }
}
