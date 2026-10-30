package com.spotpulse.telegram;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** No request/response logging, tracing, exception causes or redirects containing the credential. */
@Component
public class TelegramClient {
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http;
    private final boolean enabled;
    public TelegramClient(@Value("${TELEGRAM_BOT_TOKEN:}") String token,
                          @Value("${spotpulse.telegram.enabled:true}") boolean enabled) {
        this.token = token;
        this.enabled = enabled && !token.isBlank();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public boolean configured() { return enabled; }
    public JsonNode me() { return call("getMe", Map.of()); }
    public JsonNode updates(long offset) {
        return call("getUpdates", Map.of("offset", offset, "timeout", 20, "limit", 50,
                "allowed_updates", new String[]{"message"}));
    }
    public void send(Long chatId, String text) {
        call("sendMessage", Map.of("chat_id", chatId, "text", text));
    }
    private JsonNode call(String method, Map<String, ?> body) {
        if (!enabled) throw new Failure(0, 30);
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            // Bound the complete body read as well as connection/response-header time.
            var response = pending.get(30, TimeUnit.SECONDS);
            // Raw Telegram descriptions can contain sensitive input. Never propagate them.
            JsonNode root = json.readTree(response.body());
            if (response.statusCode() != 200 || !root.path("ok").asBoolean()) {
                int status = root.path("error_code").asInt(response.statusCode());
                long retry = Math.max(1, root.path("parameters").path("retry_after").asLong(5));
                throw new Failure(status, retry);
            }
            return root.path("result");
        } catch (Failure safe) { throw safe;
        } catch (InterruptedException interrupted) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt(); throw new Failure(0, 5);
        } catch (Exception ignored) {
            if (pending != null) pending.cancel(true);
            throw new Failure(0, 5);
        }
    }
    public static final class Failure extends RuntimeException {
        public final int status;
        public final long retrySeconds;
        Failure(int status, long retrySeconds) {
            super("Telegram service unavailable", null, false, false);
            this.status = status; this.retrySeconds = retrySeconds;
        }
    }
    @PreDestroy void stop() { http.shutdownNow(); }
}
