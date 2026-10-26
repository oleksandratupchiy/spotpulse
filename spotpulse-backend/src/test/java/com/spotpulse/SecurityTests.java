package com.spotpulse;

import com.spotpulse.dto.*;
import com.spotpulse.model.User;
import com.spotpulse.model.enums.*;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.*;
import com.spotpulse.security.*;
import com.spotpulse.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:security;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.h2.console.enabled=false"})
@Transactional
@Import(SecurityTests.OAuthTestConfiguration.class)
class SecurityTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class OAuthTestConfiguration {
        // Dummy registration is test-only: no Google requests or environment credentials are used.
        @Bean @Primary ClientRegistrationRepository testRegistration() {
            return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId("qa-client").clientSecret("qa-test-only").scope("openid", "email", "profile")
                    .redirectUri("http://localhost:8080/login/oauth2/code/google").build());
        }
    }
    @Autowired GoogleIdentityService identities;
    @Autowired UserRepository userRepository;
    @Autowired SpaceRepository spaceRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired BookingService bookings;
    @Autowired UserService users;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    AppOidcUser admin, owner, otherOwner, member, otherMember;
    Long ownSpace, otherSpace, legacySpace, ownRoom, otherRoom, ownBooking, otherBooking;
    LocalDateTime start;

    DefaultOidcUser oidc(String subject, String email, boolean verified, Map<String, Object> extra) {
        var claims = new HashMap<String, Object>(Map.of("sub", subject, "email", email, "email_verified", verified, "name", "Test member", "iss", "https://accounts.google.com"));
        claims.putAll(extra);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("qa-id-token", Instant.now(), Instant.now().plusSeconds(3600), claims));
    }
    AppOidcUser actor(String subject, Role role) {
        var identity = oidc(subject, subject + "@example.test", true, Map.of());
        var principal = identities.resolve(identity);
        User user = userRepository.findById(principal.getUserId()).orElseThrow();
        user.setRole(role); userRepository.saveAndFlush(user); // trusted isolated fixture, not a public role assignment
        return new AppOidcUser(user, identity);
    }
    OAuth2AuthenticationToken token(AppOidcUser user) { return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"); }
    void as(AppOidcUser user) { SecurityContextHolder.getContext().setAuthentication(token(user)); }
    SpaceCreateDto space(String name) { return new SpaceCreateDto(name, null, "Street 1", "Kyiv", null, null, 50.45, 30.52); }
    String spaceJson = "{\"name\":\"New space\",\"address\":\"Street\",\"city\":\"Kyiv\"}";
    String bookingJson(Long room, Long user) {
        return "{\"roomId\":" + room + (user == null ? "" : ",\"userId\":" + user) + ",\"startTime\":\"" + start.plusHours(4) + "\",\"endTime\":\"" + start.plusHours(5) + "\"}";
    }
    @BeforeEach void seed() {
        admin = actor("admin", Role.ADMIN); owner = actor("owner", Role.OWNER); otherOwner = actor("other-owner", Role.OWNER);
        member = actor("member", Role.USER); otherMember = actor("other-member", Role.USER);
        as(owner); ownSpace = spaces.create(space("Owner One Hub")).getId(); ownRoom = rooms.create(new RoomCreateDto("Oak", 4, BigDecimal.TEN, ownSpace)).getId();
        as(otherOwner); otherSpace = spaces.create(space("Owner Two Hub")).getId(); otherRoom = rooms.create(new RoomCreateDto("Birch", 4, BigDecimal.TEN, otherSpace)).getId();
        as(admin); legacySpace = spaces.create(space("Legacy")).getId(); spaceRepository.findById(legacySpace).orElseThrow().setOwner(null);
        start = LocalDate.now().plusDays(3).atTime(10, 0);
        as(member); ownBooking = bookings.create(new BookingCreateDto(ownRoom, null, start, start.plusHours(1), null)).getId();
        as(otherMember); otherBooking = bookings.create(new BookingCreateDto(otherRoom, null, start, start.plusHours(1), null)).getId();
        bookings.create(new BookingCreateDto(otherRoom, null, start.plusHours(2), start.plusHours(3), null));
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void anonymousDiscoveryRemainsPublicButSensitiveReadsAndManagementRequireLogin() throws Exception {
        for (String path : List.of("/api/spaces?skip=0&limit=2", "/api/spaces/map", "/api/spaces/autocomplete?q=Owner", "/api/rooms/" + ownRoom, "/spaces/" + ownSpace, "/robots.txt"))
            mvc.perform(get(path)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(head("/spaces/{id}", ownSpace)).andExpect(status().isOk());
        mvc.perform(head("/robots.txt")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
        for (String path : List.of("/api/users", "/api/bookings", "/api/analytics/bookings", "/api/management/spaces", "/spaces/new"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/spaces").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isUnauthorized());
    }

    @Test void csrfRequiredForEveryUnsafeMethodIncludingLegacyMvcAndLogout() throws Exception {
        mvc.perform(post("/api/spaces").with(authentication(token(owner))).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isForbidden());
        mvc.perform(post("/api/spaces").with(authentication(token(owner))).with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isForbidden());
        mvc.perform(post("/spaces").with(authentication(token(owner))).param("name", "Hub").param("city", "Kyiv").param("address", "Street")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").with(authentication(token(member)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/spaces").with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isCreated());
    }

    @Test void newGoogleUsersHaveDefaultRoleAndClaimsCannotEscalateIt() {
        var user = identities.resolve(oidc("fresh", "fresh@example.test", true, Map.of("role", "ADMIN", "roles", List.of("OWNER", "ADMIN"))));
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_USER");
        assertThat(userRepository.findById(user.getUserId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test void stableProviderSubjectWinsOverChangedDisplayNameOrEmailAndUnverifiedIdentityIsRejected() {
        var next = identities.resolve(oidc("owner", "changed@example.test", true, Map.of("name", "Different name")));
        assertThat(next.getUserId()).isEqualTo(owner.getUserId());
        assertThat(next.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_OWNER");
        assertThatThrownBy(() -> identities.resolve(oidc("unverified", "unverified@example.test", false, Map.of())))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
    }

    @Test void matchingLegacyEmailNeverLinksOrInheritsItsRole() {
        as(admin); users.create(new UserCreateDto("Legacy administrator", "legacy@example.test", Role.ADMIN));
        long before = userRepository.count();
        assertThatThrownBy(() -> identities.resolve(oidc("new-google-subject", "LEGACY@example.test", true, Map.of())))
                .isInstanceOfSatisfying(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
                        failure -> assertThat(failure.getError().getErrorCode()).isEqualTo("account_migration_required"));
        assertThat(userRepository.count()).isEqualTo(before);
        assertThat(userRepository.findByEmailIgnoreCase("legacy@example.test").orElseThrow().getOauthSubject()).isNull();
    }

    @Test void profileReturnsOnlySafeFieldsWithMockOidcLogin() throws Exception {
        String body = mvc.perform(get("/api/auth/me").with(oidcLogin().oidcUser(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.id").value(member.getUserId())).andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.email").value("member@example.test")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("qa-id-token", "accessToken", "refreshToken", "oauthSubject", "credentials", "idToken");
    }

    @Test void userCannotImpersonateAnotherBookerOrReadOrCancelTheirBookings() throws Exception {
        mvc.perform(get("/api/bookings?skip=0&limit=10").with(authentication(token(member))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(ownBooking));
        mvc.perform(get("/api/bookings").param("userId", otherMember.getUserId().toString()).with(authentication(token(member)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings/{id}", otherBooking).with(authentication(token(member)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/{id}/cancel", otherBooking).with(authentication(token(member))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bookingJson(ownRoom, otherMember.getUserId()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bookingJson(ownRoom, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value(member.getUserId()));
    }

    @Test void userCanCancelOwnFutureBookingButCannotPerformManagementOperations() throws Exception {
        mvc.perform(post("/api/bookings/{id}/cancel", ownBooking).with(authentication(token(member))).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/bookings/{id}/cancel", ownBooking).with(authentication(token(member))).with(csrf())).andExpect(status().isOk());
        for (String path : List.of("/api/users", "/api/management/spaces", "/api/analytics/bookings"))
            mvc.perform(get(path).with(authentication(token(member)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/spaces").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/bookings/{id}", ownBooking).with(authentication(token(member))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/bookings/{id}", ownBooking).with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bookingJson(ownRoom, null))).andExpect(status().isForbidden());
        mvc.perform(post("/api/users").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Escalate\",\"email\":\"hack@example.test\",\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
    }

    @Test void ownerCanManageOnlyTheirSpacesAndCannotChooseAnotherOwnerThroughRequestBody() throws Exception {
        mvc.perform(get("/api/management/spaces?skip=0&limit=10").with(authentication(token(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(put("/api/spaces/{id}", ownSpace).with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isOk());
        mvc.perform(put("/api/spaces/{id}", otherSpace).with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/spaces/{id}", otherSpace).with(authentication(token(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/spaces/{id}", otherSpace).with(authentication(token(owner)))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/spaces/{id}/owner", ownSpace).with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":" + otherOwner.getUserId() + "}")).andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/spaces").with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson.substring(0, spaceJson.length()-1) + ",\"ownerId\":" + otherOwner.getUserId() + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.<Number>read(created, "$.id").longValue();
        assertThat(spaceRepository.findById(id).orElseThrow().getOwner().getId()).isEqualTo(owner.getUserId());
    }

    @Test void ownerCannotModifyForeignRoomsOrMoveOwnRoomIntoForeignSpace() throws Exception {
        String room = "{\"name\":\"Room\",\"capacity\":4,\"hourlyRate\":10,\"spaceId\":" + otherSpace + "}";
        mvc.perform(post("/api/rooms").with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(room)).andExpect(status().isForbidden());
        for (Long id : List.of(ownRoom, otherRoom)) mvc.perform(put("/api/rooms/{id}", id).with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(room)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/rooms/{id}", otherRoom).with(authentication(token(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/rooms").with(authentication(token(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(room.replace(otherSpace.toString(), ownSpace.toString()))).andExpect(status().isCreated());
    }

    @Test void legacyMvcRoutesCannotBypassOwnershipOrRoleChecks() throws Exception {
        mvc.perform(get("/spaces/{id}/edit", otherSpace).with(authentication(token(owner)))).andExpect(status().isForbidden());
        mvc.perform(post("/spaces/{id}", otherSpace).with(authentication(token(owner))).with(csrf()).param("name", "Hijacked").param("city", "Kyiv").param("address", "Street")).andExpect(status().isForbidden());
        mvc.perform(post("/spaces/{id}/delete", otherSpace).with(authentication(token(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/spaces/new").with(authentication(token(member)))).andExpect(status().isForbidden());
        mvc.perform(get("/spaces/{id}/edit", ownSpace).with(authentication(token(owner)))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("_csrf")));
    }

    @Test void ownersSeeOnlyTheirBookingsAndScopedAnalyticsEvenAfterPlatformCacheIsWarm() throws Exception {
        String path = "/api/analytics/bookings?from=" + start.toLocalDate() + "&to=" + start.toLocalDate();
        mvc.perform(get(path).with(authentication(token(admin)))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get(path).with(authentication(token(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.popularRooms[0].spaceName").value("Owner One Hub"));
        mvc.perform(get(path).with(authentication(token(otherOwner)))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.popularRooms[0].spaceName").value("Owner Two Hub"));
        mvc.perform(get(path).with(authentication(token(member)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/bookings?skip=0&limit=10").with(authentication(token(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(post("/api/bookings/{id}/cancel", ownBooking).with(authentication(token(owner))).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/bookings/{id}/cancel", otherBooking).with(authentication(token(owner))).with(csrf())).andExpect(status().isForbidden());
    }

    @Test void adminCanManageRolesAndExplicitlyAssignLegacyOwnershipWithoutPublicEscalation() throws Exception {
        assertThat(spaceRepository.findById(legacySpace).orElseThrow().getOwner()).isNull();
        mvc.perform(put("/api/admin/spaces/{id}/owner", legacySpace).with(authentication(token(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":" + owner.getUserId() + "}")).andExpect(status().isOk());
        mvc.perform(get("/api/management/spaces?skip=0&limit=10").with(authentication(token(owner)))).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/users?skip=0&limit=10").with(authentication(token(admin)))).andExpect(status().isOk());
        mvc.perform(put("/api/users/{id}", member.getUserId()).with(authentication(token(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test member\",\"email\":\"member@example.test\",\"role\":\"OWNER\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/spaces").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isCreated());
        mvc.perform(put("/api/users/{id}", member.getUserId()).with(authentication(token(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test member\",\"email\":\"member@example.test\",\"role\":\"USER\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/spaces").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(spaceJson)).andExpect(status().isForbidden());
    }

    @Test void lastAdminAndLinkedIdentityCannotBeDestroyedThroughUserManagement() throws Exception {
        mvc.perform(delete("/api/users/{id}", admin.getUserId()).with(authentication(token(admin))).with(csrf())).andExpect(status().isConflict());
        mvc.perform(put("/api/users/{id}", admin.getUserId()).with(authentication(token(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Admin\",\"email\":\"admin@example.test\",\"role\":\"USER\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/users/{id}", member.getUserId()).with(authentication(token(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Member\",\"email\":\"different@example.test\",\"role\":\"USER\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/users/{id}", owner.getUserId()).with(authentication(token(admin))).with(csrf())).andExpect(status().isConflict());
    }

    @Test void startedBookingsCannotBeCancelledAndMembersCannotCreateCancelledRecords() throws Exception {
        var booking = bookingRepository.findById(ownBooking).orElseThrow();
        booking.setStartTime(LocalDateTime.now().minusHours(2)); booking.setEndTime(LocalDateTime.now().minusHours(1));
        bookingRepository.saveAndFlush(booking);
        mvc.perform(post("/api/bookings/{id}/cancel", ownBooking).with(authentication(token(member))).with(csrf())).andExpect(status().isConflict());
        String cancelled = bookingJson(ownRoom, null).replace("}", ",\"status\":\"CANCELLED\"}");
        mvc.perform(post("/api/bookings").with(authentication(token(member))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cancelled)).andExpect(status().isForbidden());
    }

    @Test void logoutInvalidatesServerSessionAndSubsequentAnonymousRequestsAreRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(token(member));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test void deletedOrInvalidPrincipalCannotContinueUsingSession() throws Exception {
        var removed = actor("removed", Role.USER); userRepository.deleteById(removed.getUserId()); userRepository.flush();
        mvc.perform(get("/api/bookings").with(authentication(token(removed)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(authentication(token(removed)))).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test void oauthEntryUsesStateMinimalScopesAndFixedCallbackAndRejectsForgedCallback() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/google").param("redirect", "https://attacker.test"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getHeader("Location");
        String decoded = java.net.URLDecoder.decode(location, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).contains("state=", "openid", "email", "profile", "redirect_uri=http://localhost:8080/login/oauth2/code/google").doesNotContain("attacker.test");
        mvc.perform(get("/login/oauth2/code/google").param("code", "forged-code").param("state", "invalid"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:5173/login?error=login_failed"));
    }
}
