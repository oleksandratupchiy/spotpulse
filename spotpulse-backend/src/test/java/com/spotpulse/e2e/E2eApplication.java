package com.spotpulse.e2e;

import com.spotpulse.SpotpulseApplication;
import com.spotpulse.model.*;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.*;
import com.spotpulse.security.AppOidcUser;
import com.spotpulse.premium.*;
import jakarta.servlet.http.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Test classpath only. Never packaged into the application or production Docker image. */
public final class E2eApplication {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "").isBlank())
            throw new IllegalStateException("E2E requires a sanitized environment and an owned temporary directory");
        Path root = Path.of(args[0]).toRealPath();
        if (!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                || !root.getFileName().toString().startsWith("spotpulse-e2e-"))
            throw new IllegalStateException("E2E refuses non-temporary storage");
        int port = Integer.parseInt(args[1]);
        if (port < 1024 || port > 65535) throw new IllegalStateException("Invalid E2E port");
        String origin = "http://localhost:" + port;
        var application = new SpringApplication(SpotpulseApplication.class, Harness.class);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("ownedE2eImageDirectory",
                        Map.of("spotpulse.images.directory", root.resolve("images").toString()))));
        application.run(
                "--spring.profiles.active=e2e", "--server.address=127.0.0.1", "--server.port=" + port,
                "--spring.datasource.url=jdbc:h2:mem:e2e-" + UUID.randomUUID(),
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
                "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.jpa.show-sql=false",
                "--spring.h2.console.enabled=false", "--spotpulse.telegram.enabled=false",
                "--spotpulse.images.storage-provider=local", "--spotpulse.images.directory=" + root.resolve("images"),
                "--spring.servlet.multipart.location=" + root,
                "--spotpulse.auth.frontend-origin=" + origin, "--spotpulse.auth.ui-base-path=/app",
                "--spotpulse.auth.redirect-uri=" + origin + "/login/oauth2/code/google",
                "--spotpulse.availability.allowed-origins=" + origin,
                "--GOOGLE_CLIENT_ID=qa2-synthetic", "--GOOGLE_CLIENT_SECRET=qa2-synthetic",
                "--server.servlet.session.cookie.secure=false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("e2e & !prod & !local")
    @Import(FixtureController.class)
    public static class Harness {
        @Bean @Order(0)
        SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            // This extra chain exists only in this explicit test bootstrap. CSRF remains enabled.
            return http.securityMatcher("/__e2e/**").authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .csrf(c -> {}).build();
        }
    }

    @RestController
    @Profile("e2e & !prod & !local")
    public static class FixtureController {
        @org.springframework.beans.factory.annotation.Autowired com.spotpulse.availability.AvailabilityAlertStore alertStore;
        @org.springframework.beans.factory.annotation.Autowired com.spotpulse.telegram.TelegramNotificationEligibility telegramEligibility;
        @org.springframework.beans.factory.annotation.Autowired com.spotpulse.telegram.TelegramClient telegramClient;

        @PostMapping("/__e2e/availability-alerts/process")
        public void processAlerts() {
            if (telegramClient.configured()) throw new IllegalStateException("E2E refuses live Telegram delivery");
            var dispatcher = new com.spotpulse.availability.AvailabilityAlertDispatcher(alertStore, telegramEligibility, telegramClient);
            try { dispatcher.process(); } finally { dispatcher.stop(); }
        }
        private final UserRepository users;
        private final SpaceRepository spaces;
        private final RoomRepository rooms;
        private final BookingRepository bookings;
        private final FeatureFlagRepository flags;
        private final org.springframework.cache.CacheManager caches;
        public FixtureController(UserRepository users, SpaceRepository spaces, RoomRepository rooms,
                                 BookingRepository bookings, FeatureFlagRepository flags, org.springframework.cache.CacheManager caches) {
            this.users = users; this.spaces = spaces; this.rooms = rooms; this.bookings = bookings; this.flags = flags; this.caches = caches;
        }

        @PostMapping("/__e2e/reset") @Transactional
        public Map<String, Object> reset() {
            // Bulk deletes execute immediately: Hibernate otherwise schedules inserts before queued deletes,
            // conflicting with deterministic unique fixture emails on the second reset.
            bookings.deleteAllInBatch(); rooms.deleteAllInBatch(); spaces.deleteAllInBatch(); users.deleteAllInBatch();
            Map<String, User> actors = new LinkedHashMap<>();
            for (String name : List.of("user", "owner", "other", "admin")) {
                User user = new User(); user.setName("QA2 " + name); user.setEmail(name + "@qa2.example.test");
                user.setOauthProvider("google"); user.setOauthSubject("qa2-" + name);
                user.setRole(name.equals("user") ? Role.USER : name.equals("admin") ? Role.ADMIN : Role.OWNER);
                actors.put(name, users.saveAndFlush(user));
            }
            List<Long> spaceIds = new ArrayList<>(), roomIds = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                Space space = new Space(); space.setName(String.format("QA2 Hub %02d", i));
                space.setCity(i <= 4 ? "Kyiv" : "Lviv"); space.setAddress("Fixture street " + i);
                space.setDescription("Isolated QA2 coworking fixture"); space.setOwner(actors.get(i == 2 ? "other" : "owner"));
                space.setPremium(i == 1); space = spaces.saveAndFlush(space); spaceIds.add(space.getId());
                Room room = new Room(); room.setName("QA2 Room " + i); room.setSpace(space); room.setCapacity(6);
                room.setHourlyRate(new BigDecimal("25.00")); roomIds.add(rooms.saveAndFlush(room).getId());
            }
            for (FeatureKey key : FeatureKey.values()) flags.save(new FeatureFlag(key, true));
            LocalDateTime from = LocalDate.now().plusDays(7).atTime(10, 0);
            Booking busy = new Booking(); busy.setRoom(rooms.findById(roomIds.get(1)).orElseThrow());
            busy.setUser(actors.get("user")); busy.setStartTime(from); busy.setEndTime(from.plusHours(1)); bookings.saveAndFlush(busy);
            caches.getCacheNames().forEach(name -> Objects.requireNonNull(caches.getCache(name)).clear());
            return Map.of("spaces", spaceIds, "rooms", roomIds, "from", from.toString(), "to", from.plusHours(1).toString());
        }

        @PostMapping("/__e2e/login/{name}")
        public void login(@PathVariable String name, HttpServletRequest request, HttpServletResponse response) {
            if (!Set.of("user", "owner", "other", "admin").contains(name)) throw new IllegalArgumentException("Unknown synthetic actor");
            User user = users.findAll().stream().filter(u -> ("qa2-" + name).equals(u.getOauthSubject())).findFirst().orElseThrow();
            var oidc = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                    new OidcIdToken("qa2-synthetic", Instant.now(), Instant.now().plusSeconds(3600),
                            Map.of("sub", user.getOauthSubject(), "email", user.getEmail(), "email_verified", true)));
            var principal = new AppOidcUser(user, oidc);
            request.getSession(true); request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        }
    }
}
