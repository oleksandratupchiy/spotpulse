package com.spotpulse.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramClientTests {
    @Test void missingCredentialDisablesClientWithoutNetwork() {
        var client = new TelegramClient("", true);
        assertThat(client.configured()).isFalse();
        assertThatThrownBy(client::me).hasMessage("Telegram service unavailable").hasNoCause();
        client.stop();
    }
    @Test void rateLimitAndTransportErrorsAreSanitized() throws Exception {
        // Synthetic credential only; no environment access or real Telegram connection.
        var client = new TelegramClient("test-only-credential", true);
        var http = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "http", http);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn("{\"ok\":false,\"error_code\":429,\"description\":\"sensitive remote input\",\"parameters\":{\"retry_after\":17}}");
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertThatThrownBy(client::me).isInstanceOfSatisfying(TelegramClient.Failure.class, failure -> {
            assertThat(failure.status).isEqualTo(429); assertThat(failure.retrySeconds).isEqualTo(17);
            assertThat(failure).hasMessage("Telegram service unavailable").hasNoCause();
        });
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("https://api.telegram.org/bot/test-only-credential")));
        assertThatThrownBy(client::me).hasMessage("Telegram service unavailable").hasNoCause();
        client.stop();
    }

    @Test void sendMessageChecksTelegramResponseAndSanitizesFailedRequests() throws Exception {
        var client = new TelegramClient("synthetic-credential", true);
        var http = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "http", http);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true,\"result\":{\"message_id\":1}}");
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertThatCode(() -> client.send(77L, "synthetic message")).doesNotThrowAnyException();
        when(response.body()).thenReturn("{\"ok\":false,\"error_code\":403,\"description\":\"private message chat credential\"}");
        assertThatThrownBy(() -> client.send(77L, "synthetic message"))
                .isInstanceOfSatisfying(TelegramClient.Failure.class, failure -> {
                    assertThat(failure.status).isEqualTo(403);
                    assertThat(failure).hasMessage("Telegram service unavailable").hasNoCause();
                });
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("https://api.telegram.org/bot/synthetic-credential")));
        assertThatThrownBy(() -> client.send(77L, "synthetic message"))
                .isInstanceOfSatisfying(TelegramClient.Failure.class, failure -> {
                    assertThat(failure.status).isZero();
                    assertThat(failure).hasMessage("Telegram service unavailable").hasNoCause();
                });
        client.stop();
    }
}
