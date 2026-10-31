package com.spotpulse.persistence;

import com.spotpulse.SpotpulseApplication;
import com.spotpulse.model.*;
import com.spotpulse.model.enums.*;
import com.spotpulse.repository.*;
import com.spotpulse.security.GoogleIdentityService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Test-only subprocess entry point. Never packaged with the production application. */
public class LocalDatabaseProcessProbe {
    public static void main(String[] args) throws Exception {
        String phase = args[0]; Path root = Path.of(args[1]).toRealPath();
        String bootstrap = phase.equals("bootstrap") ? Files.readString(root.resolve("candidate-id.txt")).strip() : "";
        if (phase.equals("write") && Files.exists(root.resolve("spotpulse.mv.db"))) throw new IllegalStateException("Refuse to seed an existing database");
        try (var context = new SpringApplicationBuilder(SpotpulseApplication.class).run(
                "--spotpulse.local.data-directory=" + root,
                "--spotpulse.test.file-root=" + root, "--server.address=127.0.0.1", "--server.port=0", "--server.shutdown=immediate",
                "--spring.jpa.show-sql=false", "--spring.jpa.hibernate.ddl-auto=update",
                "--SPOTPULSE_BOOTSTRAP_ADMIN_ID=" + bootstrap)) {
            String expected = "jdbc:h2:file:" + root.resolve("spotpulse").toString().replace('\\', '/');
            require(expected.equals(context.getEnvironment().getProperty("spring.datasource.url")), "Local profile default URL was not used");
            var users = context.getBean(UserRepository.class); var spaces = context.getBean(SpaceRepository.class);
            var rooms = context.getBean(RoomRepository.class); var bookings = context.getBean(BookingRepository.class);
            var alerts = context.getBean(com.spotpulse.availability.AvailabilityAlertRepository.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                if (phase.equals("write")) {
                    User owner = user("owner", Role.OWNER); users.save(owner);
                    User member = user("member", Role.USER); users.save(member);
                    User candidate = user("candidate", Role.USER); users.saveAndFlush(candidate);
                    Space space = new Space(); space.setName("Persistence test space"); space.setCity("Kyiv");
                    space.setAddress("Isolated test address"); space.setOwner(owner); space.setLatitude(50.45); space.setLongitude(30.52); spaces.save(space);
                    Room room = new Room(); room.setSpace(space); room.setName("Persistence test room"); room.setCapacity(4); room.setHourlyRate(BigDecimal.TEN); rooms.save(room);
                    Booking booking = new Booking(); booking.setUser(member); booking.setRoom(room);
                    booking.setStartTime(LocalDateTime.of(2030, 1, 1, 10, 0)); booking.setEndTime(LocalDateTime.of(2030, 1, 1, 11, 0)); bookings.saveAndFlush(booking);
                    var alert = new com.spotpulse.availability.AvailabilityAlert(); alert.setUser(member); alert.setRoom(room);
                    alert.setStartTime(booking.getStartTime()); alert.setEndTime(booking.getEndTime()); alerts.saveAndFlush(alert);
                    var pending = new com.spotpulse.availability.AvailabilityAlert(); pending.setUser(member); pending.setRoom(room);
                    pending.setStartTime(booking.getStartTime().plusMinutes(15)); pending.setEndTime(booking.getEndTime());
                    pending.setStatus(com.spotpulse.availability.AvailabilityAlert.Status.READY);
                    pending.setAttempts(2); pending.setDeliveryReason("bot_unavailable"); pending.setNextAttemptAt(Instant.parse("2030-01-01T09:00:00Z")); alerts.saveAndFlush(pending);
                    try { Files.writeString(root.resolve("candidate-id.txt"), candidate.getId().toString(), StandardOpenOption.CREATE_NEW); }
                    catch (java.io.IOException error) { throw new IllegalStateException(error); }
                } else {
                    require(users.count() == 3 && spaces.count() == 1 && rooms.count() == 1 && bookings.count() == 1, "Records were lost across restart");
                    User owner = users.findByOauthProviderAndOauthSubject("google", "persistence-owner").orElseThrow();
                    User member = users.findByOauthProviderAndOauthSubject("google", "persistence-member").orElseThrow();
                    User candidate = users.findByOauthProviderAndOauthSubject("google", "persistence-candidate").orElseThrow();
                    require(owner.getRole() == Role.OWNER && member.getRole() == Role.USER && candidate.getRole() == Role.ADMIN, "Roles/bootstrap did not persist");
                    Space space = spaces.findAll().getFirst(); Room room = rooms.findAll().getFirst(); Booking booking = bookings.findAll().getFirst();
                    require(space.getOwner().getId().equals(owner.getId()) && room.getSpace().getId().equals(space.getId()), "Space ownership/room relationship changed");
                    require(booking.getUser().getId().equals(member.getId()) && booking.getRoom().getId().equals(room.getId()) && booking.getStatus() == BookingStatus.CONFIRMED, "Booking relationships/status changed");
                    require(alerts.count() == 2, "Availability subscriptions were lost across restart");
                    var restored = alerts.findAll();
                    require(restored.stream().allMatch(a -> a.getUser().getId().equals(member.getId()) && a.getRoom().getId().equals(room.getId()) && a.getNotifiedAt() == null), "Subscription ownership or unsent state changed");
                    require(restored.stream().anyMatch(a -> a.getStatus() == com.spotpulse.availability.AvailabilityAlert.Status.ACTIVE && a.getStartTime().equals(booking.getStartTime())), "Active subscription interval was lost");
                    require(restored.stream().anyMatch(a -> a.getStatus() == com.spotpulse.availability.AvailabilityAlert.Status.READY && a.getAttempts() == 2 && "bot_unavailable".equals(a.getDeliveryReason()) && a.getNextAttemptAt().equals(Instant.parse("2030-01-01T09:00:00Z"))), "Pending notification/retry metadata was lost");
                    var identity = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), new OidcIdToken("test-only-identity", Instant.now(), Instant.now().plusSeconds(60),
                            Map.of("sub", "persistence-owner", "email", "owner@example.test", "email_verified", true, "name", "Changed display name")));
                    var resolved = context.getBean(GoogleIdentityService.class).resolve(identity);
                    require(resolved.getUserId().equals(owner.getId()) && users.count() == 3, "Stable OAuth identity mapping changed");
                }
            });
            System.out.println("PERSISTENCE_PHASE_OK " + phase);
        }
    }
    private static User user(String label, Role role) {
        User user = new User(); user.setName("Persistence test " + label); user.setEmail(label + "@example.test");
        user.setRole(role); user.setOauthProvider("google"); user.setOauthSubject("persistence-" + label); return user;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
