package com.spotpulse.deployment;

import com.spotpulse.model.*;
import com.spotpulse.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:deployment-http;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "server.forward-headers-strategy=native", "server.tomcat.remoteip.protocol-header=x-forwarded-proto",
        "server.servlet.session.cookie.secure=true", "spotpulse.auth.frontend-origin=https://assigned-service.onrender.com",
        "spotpulse.auth.ui-base-path=/app", "spotpulse.auth.redirect-uri=https://assigned-service.onrender.com/login/oauth2/code/google",
        "spotpulse.availability.allowed-origins=https://assigned-service.onrender.com",
        "spotpulse.seo.public-base-url=https://assigned-service.onrender.com", "spotpulse.seo.indexable=true"})
@Import(DeploymentHttpTests.TestGoogle.class)
class DeploymentHttpTests {
    static final String ORIGIN = "https://assigned-service.onrender.com";
    @TestConfiguration(proxyBeanMethods = false)
    static class TestGoogle {
        @Bean @Primary ClientRegistrationRepository registration() {
            return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId("dummy-client").clientSecret("dummy-secret").scope("openid", "email", "profile")
                    .redirectUri(ORIGIN + "/login/oauth2/code/google").build());
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired SpaceRepository spaces;
    @Autowired RoomRepository rooms;
    Long spaceId, roomId;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    @BeforeEach void seed() {
        var space = new Space(); space.setName("Deployment Hub"); space.setCity("Kyiv"); space.setAddress("Street");
        spaceId = spaces.saveAndFlush(space).getId();
        var room = new Room(); room.setName("Deployment Room"); room.setCapacity(4); room.setHourlyRate(java.math.BigDecimal.TEN); room.setSpace(space);
        roomId = rooms.saveAndFlush(room).getId();
    }
    @AfterEach void close() { http.close(); }
    URI url(String path) { return URI.create("http://localhost:" + port + path); }
    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(url(path)).header("X-Forwarded-Proto", "https").timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void knownSpaDeepLinksServeHtmlWithoutChangingLegacyRoutes() throws Exception {
        assertThat(get("/").headers().firstValue("Location")).contains("/app/spaces");
        for (String path : java.util.List.of("/app", "/app/", "/app/spaces", "/app/spaces/42", "/app/rooms/42", "/app/login", "/app/account", "/app/bookings", "/app/owner", "/app/owner/spaces/new", "/app/owner/spaces/42", "/app/admin", "/app/analytics")) {
            var response = get(path); assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).contains("SpotPulse test SPA fixture");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("X-Robots-Tag")).contains("noindex");
        }
        assertThat(get("/spaces").body()).contains("Deployment Hub").doesNotContain("test SPA fixture");
        assertThat(get("/spaces/" + spaceId).body()).contains("Deployment Hub", ORIGIN + "/spaces/" + spaceId).doesNotContain("test SPA fixture");
        assertThat(get("/api/spaces/" + spaceId).body()).contains("\"name\":\"Deployment Hub\"").doesNotContain("test SPA fixture");
        assertThat(get("/robots.txt").body()).contains("Sitemap: " + ORIGIN + "/sitemap.xml");
        assertThat(get("/sitemap.xml").statusCode()).isEqualTo(200);
        assertThat(get("/sitemaps/spaces-0.xml").body()).contains(ORIGIN + "/spaces/" + spaceId);
    }
    @Test void staticApiOauthAndUnknownPathsAreNeverCaughtBySpaFallback() throws Exception {
        assertThat(get("/app/assets/test-app.js").body()).contains("document.getElementById").doesNotContain("test SPA fixture");
        for (String path : java.util.List.of("/app/assets/missing.js", "/app/unknown", "/api/rooms/not-a-number", "/api/users", "/h2-console")) {
            var response = get(path); assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(response.body()).doesNotContain("test SPA fixture");
        }
    }
    @Test void forwardedHttpsEnablesHstsAndSecureHttpOnlyLaxSessionCookies() throws Exception {
        var response = get("/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var csrf = JsonMapper.builder().build().readTree(response.body());
        var request = HttpRequest.newBuilder(url("/api/bookings")).header("X-Forwarded-Proto", "https")
                .header("Cookie", cookie.split(";", 2)[0]).header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(url("/api/bookings")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    @Test void oauthUsesFixedHttpsCallbackAndFailureReturnsToSameOriginSpa() throws Exception {
        var response = get("/oauth2/authorization/google"); assertThat(response.statusCode()).isEqualTo(302);
        var location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.getScheme()).isEqualTo("https"); assertThat(location.getHost()).isEqualTo("accounts.google.com");
        assertThat(URLDecoder.decode(location.getRawQuery(), StandardCharsets.UTF_8)).contains("redirect_uri=" + ORIGIN + "/login/oauth2/code/google");
        var failed = get("/login/oauth2/code/google?error=access_denied");
        assertThat(failed.statusCode()).isEqualTo(302);
        assertThat(failed.headers().firstValue("Location")).contains(ORIGIN + "/app/login?error=login_failed");
    }
    @Test void healthIsPublicAndOnlyReportsReadinessWithoutDetails() throws Exception {
        var response = get("/health"); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void productionWebsocketOriginIsExactAndChannelRemainsReadOnly() throws Exception {
        var messages = new CompletableFuture<String>(); var closed = new CompletableFuture<Integer>();
        var socket = http.newWebSocketBuilder().header("Origin", ORIGIN).header("X-Forwarded-Proto", "https")
                .buildAsync(URI.create("ws://localhost:" + port + "/api/rooms/availability/ws?roomId=" + roomId), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) { messages.complete(text.toString()); ws.request(1); return null; }
                    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { closed.complete(code); return null; }
                }).get(20, TimeUnit.SECONDS);
        try {
            assertThat(messages.get(20, TimeUnit.SECONDS)).contains("ready", "\"roomId\":" + roomId);
            socket.sendText("book", true).join(); assertThat(closed.get(20, TimeUnit.SECONDS)).isEqualTo(1008);
        } finally { socket.abort(); }
        assertThatThrownBy(() -> http.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                .buildAsync(URI.create("ws://localhost:" + port + "/api/rooms/availability/ws?roomId=" + roomId), new WebSocket.Listener() {}).get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(WebSocketHandshakeException.class);
    }
}
