package com.spotpulse.availability;

import com.spotpulse.dto.*;
import com.spotpulse.model.User;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.UserRepository;
import com.spotpulse.security.AppOidcUser;
import com.spotpulse.service.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

abstract class AvailabilityTestSupport {
    @Autowired UserRepository users;
    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired BookingService bookings;
    @Autowired AvailabilityService availability;
    @Autowired AvailabilitySocket hub;
    @Value("${local.server.port}") int port;
    AppOidcUser actor;
    Long room;
    LocalDateTime from = LocalDate.now().plusDays(10).atTime(10, 0);
    LocalDateTime to = from.plusHours(8);
    final List<SocketClient> sockets = new ArrayList<>();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    void fixture() {
        String name = UUID.randomUUID().toString();
        User user = new User(); user.setName("Private customer"); user.setEmail(name + "@private.test"); user.setRole(Role.ADMIN);
        user.setOauthProvider("google"); user.setOauthSubject(name); user = users.saveAndFlush(user);
        var oidc = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("synthetic-test-only", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", name)));
        actor = new AppOidcUser(user, oidc); asActor();
        Long space = spaces.create(new SpaceCreateDto("Test Hub", "", "Street", "Kyiv", null)).getId();
        room = rooms.create(new RoomCreateDto("Test room", 4, BigDecimal.TEN, space)).getId();
    }
    void asActor() { SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(actor, actor.getAuthorities(), "google")); }
    BookingDto create() { asActor(); return bookings.create(new BookingCreateDto(room, null, from.plusHours(2), from.plusHours(3), null)); }
    URI endpoint(Long id) { return URI.create("http://localhost:" + port + "/api/rooms/" + id + "/availability?from=" + from + "&to=" + to); }
    HttpResponse<String> snapshot(Long id) throws Exception {
        return http.send(HttpRequest.newBuilder(endpoint(id)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    SocketClient connect(Long id) throws Exception { return connect(id, "http://localhost:5173"); }
    SocketClient connect(Long id, String origin) throws Exception {
        var client = new SocketClient();
        var builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(30));
        if (origin != null) builder.header("Origin", origin);
        client.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/api/rooms/availability/ws?roomId=" + id), client).get(35, TimeUnit.SECONDS);
        sockets.add(client);
        client.take(); // ready: handshake-to-ready overhead is measured separately by the experiment
        return client;
    }
    void cleanup() throws Exception {
        sockets.forEach(SocketClient::close); sockets.clear(); SecurityContextHolder.clearContext(); http.close();
    }
    record Received(String json, long at) { }
    static class SocketClient implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<Received> messages = new LinkedBlockingQueue<>(64);
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder pending = new StringBuilder();
        final java.util.concurrent.atomic.AtomicLong receivedMessages = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong receivedBytes = new java.util.concurrent.atomic.AtomicLong();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                receivedMessages.incrementAndGet(); receivedBytes.addAndGet(pending.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                messages.offer(new Received(pending.toString(), System.nanoTime())); pending.setLength(0);
            }
            ws.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { closed.complete(status); return null; }
        Received take() throws Exception {
            var result = messages.poll(30, TimeUnit.SECONDS);
            if (result == null) throw new AssertionError("No WebSocket notification within 30 seconds");
            return result;
        }
        void close() { if (socket != null) socket.abort(); }
    }
}
