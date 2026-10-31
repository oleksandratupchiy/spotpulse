package com.spotpulse.telegram;

import com.spotpulse.model.User;
import com.spotpulse.repository.UserRepository;
import com.spotpulse.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:telegram;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@Transactional
class TelegramIntegrationTests {
    @Autowired TelegramLinkService links;
    @Autowired TelegramPollingStore polling;
    @Autowired TelegramPollRepository states;
    @Autowired TelegramAccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired TelegramRuntime runtime;
    @Autowired WebApplicationContext context;
    @MockitoBean TelegramClient client;
    AppOidcUser member, other;
    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() {
        // The mocked client is unconfigured at ApplicationReadyEvent, so no worker starts.
        when(client.configured()).thenReturn(true);
        runtime.ready(123L, "spotpulse_test_bot");
        member = actor("member"); other = actor("other"); as(member);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); runtime.unavailable(); }
    AppOidcUser actor(String name) {
        User user = new User(); user.setName(name); user.setEmail(name + "@telegram.test");
        user.setOauthProvider("google"); user.setOauthSubject(name);
        users.saveAndFlush(user);
        var identity = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("test-only-identity", Instant.now(), Instant.now().plusSeconds(300),
                        Map.of("sub", name, "email", user.getEmail(), "email_verified", true)));
        return new AppOidcUser(user, identity);
    }
    OAuth2AuthenticationToken auth(AppOidcUser user) { return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"); }
    void as(AppOidcUser user) { SecurityContextHolder.getContext().setAuthentication(auth(user)); }
    String code(TelegramLinkService.Link link) { return link.url().split("start=")[1]; }
    String updates(long id, long chat, String type, String code) {
        return "[{\"update_id\":" + id + ",\"message\":{\"chat\":{\"id\":" + chat + ",\"type\":\"" + type
                + "\"},\"from\":{\"id\":" + chat + ",\"is_bot\":false},\"text\":\"/start " + code + "\"}}]";
    }

    @Test void codesAreRandomHashedExpiringAndReplaced() {
        var first = links.connect(); var second = links.connect();
        assertThat(code(first)).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(code(second));
        var account = accounts.findById(member.getUserId()).orElseThrow();
        assertThat(account.getCodeHash()).isEqualTo(TelegramLinkService.hash(code(second))).isNotEqualTo(code(second));
        assertThat(first.expiresAt()).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(301));
        assertThat(links.accept(123L, 77L, code(first))).contains("invalid or expired");
        account.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(links.accept(123L, 77L, code(second))).contains("invalid or expired");
        assertThat(links.status().connected()).isFalse();
        verify(client, never()).send(anyLong(), anyString());
    }

    @Test void validPrivateStartLinksOnceAndPersistsOffsetWithIgnoredUpdates() {
        String code = code(links.connect());
        assertThat(polling.acquire(123L, "worker-one").acquired()).isTrue();
        assertThat(polling.acquire(123L, "worker-two").acquired()).isFalse();
        assertThat(polling.process(123L, "worker-two", json.readTree(updates(10, 77, "private", code)))).isEmpty();
        assertThat(polling.process(123L, "worker-one", json.readTree(updates(10, -77, "group", code)))).isEmpty();
        assertThat(links.status().connected()).isFalse();
        var result = polling.process(123L, "worker-one", json.readTree(updates(11, 77, "private", code)));
        assertThat(result).hasSize(1); assertThat(result.getFirst().text()).contains("connected to SpotPulse");
        assertThat(links.status().connected()).isTrue();
        assertThat(links.status().bookingNotificationsEnabled()).isTrue();
        assertThat(accounts.findById(member.getUserId()).orElseThrow().getCodeHash()).isNull();
        assertThat(polling.process(123L, "worker-one", json.readTree(updates(11, 77, "private", code)))).isEmpty();
        assertThat(links.accept(123L, 88L, code)).contains("invalid or expired");
        polling.release(123L, "worker-one");
        assertThat(polling.acquire(123L, "restarted-worker").offset()).isEqualTo(12);
    }

    @Test void chatCannotMoveToAnotherAccountAndConflictConsumesItsCode() {
        assertThat(links.accept(123L, 77L, code(links.connect()))).contains("connected to SpotPulse");
        as(other); String otherCode = code(links.connect());
        assertThat(links.accept(123L, 77L, otherCode)).contains("already connected");
        assertThat(links.status().connected()).isFalse();
        assertThat(accounts.findById(other.getUserId()).orElseThrow().getCodeHash()).isNull();
        as(member); assertThat(links.status().connected()).isTrue();
    }

    @Test void expiredLeaseCanBeTakenOverAndPreviousWorkerCannotCommit() {
        String code = code(links.connect());
        polling.acquire(123L, "crashed-worker");
        states.findById(123L).orElseThrow().setLeaseUntil(Instant.now().minusSeconds(1));
        assertThat(polling.acquire(123L, "replacement-worker").acquired()).isTrue();
        polling.release(123L, "crashed-worker");
        assertThat(polling.acquire(123L, "third-worker").acquired()).isFalse();
        assertThat(polling.process(123L, "crashed-worker", json.readTree(updates(20, 77, "private", code)))).isEmpty();
        assertThat(links.status().connected()).isFalse();
    }

    @Test void disconnectRevokesCodesAndOnlyChangesCurrentAccount() {
        links.accept(123L, 77L, code(links.connect()));
        as(other); String pending = code(links.connect()); links.disconnect();
        assertThat(links.accept(123L, 88L, pending)).contains("invalid or expired");
        assertThat(accounts.findById(member.getUserId()).orElseThrow().getChatId()).isEqualTo(77L);
        as(member); links.disconnect(); assertThat(links.status().connected()).isFalse();
        assertThat(accounts.findById(member.getUserId()).orElseThrow().getChatId()).isNull();
    }

    @Test void endpointsRequireAuthenticationCsrfAndExposeOnlySafeStatus() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/account/telegram")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/telegram/connect").with(authentication(auth(member)))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/account/telegram").with(authentication(auth(member)))).andExpect(status().isForbidden());
        String response = mvc.perform(post("/api/account/telegram/connect").with(authentication(auth(member))).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://t.me/spotpulse_test_bot?start=")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("chatId", "userId", "codeHash", "botToken");
        String state = mvc.perform(get("/api/account/telegram").with(authentication(auth(member))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(state).doesNotContain("chatId", "codeHash", "start=", "userId");
        mvc.perform(delete("/api/account/telegram").with(authentication(auth(member))).with(csrf())).andExpect(status().isNoContent());
    }

    @Test void configuredButUnavailableBotBlocksLinkCreationWithoutNetwork() {
        runtime.unavailable();
        assertThat(links.status()).isEqualTo(new TelegramLinkService.Status(true, false, false, null, true));
        assertThatThrownBy(() -> links.connect()).isInstanceOfSatisfying(
                org.springframework.web.server.ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(503));
        verify(client, never()).me(); verify(client, never()).updates(anyLong()); verify(client, never()).send(anyLong(), anyString());
        runtime.ready(123L, "spotpulse_test_bot");
        assertThat(links.status().available()).isTrue();
    }

    @Test void unavailableIntegrationDoesNotRequireNetworkAndReportsNoSecrets() {
        when(client.configured()).thenReturn(false); runtime.unavailable();
        assertThat(links.status()).isEqualTo(new TelegramLinkService.Status(false, false, false, null, true));
        assertThatThrownBy(() -> links.connect()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(client, never()).me(); verify(client, never()).updates(anyLong()); verify(client, never()).send(anyLong(), anyString());
    }

    @Test void preferencesDefaultOnAndExplicitOptOutSurvivesRelinking() throws Exception {
        assertThat(links.status().bookingNotificationsEnabled()).isTrue();
        mvc.perform(patch("/api/account/telegram/preferences").with(authentication(auth(member)))
                .contentType("application/json").content("{\"bookingNotificationsEnabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/account/telegram/preferences").with(authentication(auth(member))).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/account/telegram/preferences").with(authentication(auth(member))).with(csrf())
                .contentType("application/json").content("{\"bookingNotificationsEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.bookingNotificationsEnabled").value(false));
        as(member); // MockMvc clears the thread's SecurityContext after each request.
        assertThat(links.accept(123L, 77L, code(links.connect()))).contains("connected to SpotPulse");
        assertThat(links.status().bookingNotificationsEnabled()).isFalse();
        links.disconnect();
        links.accept(123L, 77L, code(links.connect()));
        assertThat(links.status().bookingNotificationsEnabled()).isFalse();
        as(other); assertThat(links.status().bookingNotificationsEnabled()).isTrue();
        as(member);
        mvc.perform(patch("/api/account/telegram/preferences").with(authentication(auth(member))).with(csrf())
                .contentType("application/json").content("{\"bookingNotificationsEnabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingNotificationsEnabled").value(true));
    }
}
