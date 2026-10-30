package com.spotpulse.availability;

import jakarta.annotation.PreDestroy;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Public, read-only invalidation channel. No booking DTOs or client commands cross this boundary. */
@Component
public class AvailabilitySocket extends AbstractWebSocketHandler {
    private record Client(Long roomId, String address, WebSocketSession session) { }
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("availability-delivery-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    @Override public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        String address = raw.getRemoteAddress() == null ? "unknown" : raw.getRemoteAddress().getAddress().getHostAddress();
        synchronized (clients) {
            if (clients.size() >= 100 || clients.values().stream().filter(c -> c.address().equals(address)).count() >= 20) {
                raw.close(CloseStatus.SERVICE_OVERLOAD); return;
            }
            raw.setTextMessageSizeLimit(128); raw.setBinaryMessageSizeLimit(128);
            if (raw instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession standard) {
                var nativeSession = standard.getNativeSession();
                nativeSession.setMaxIdleTimeout(120000);
                nativeSession.getAsyncRemote().setSendTimeout(2000);
                // Bound Tomcat's synchronous BasicRemote sends too (Boot's embedded servlet container).
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 2000L);
            }
            var session = new ConcurrentWebSocketSessionDecorator(raw, 2000, 4096);
            clients.put(raw.getId(), new Client((Long) raw.getAttributes().get("roomId"), address, session));
        }
        send(clients.get(raw.getId()), "ready");
    }

    @Order(0) @TransactionalEventListener
    public void changed(AvailabilityChanged event) {
        // Never block a committed booking on network delivery or Telegram. Reconciliation repairs dropped signals.
        submit(() -> clients.values().stream().filter(c -> c.roomId().equals(event.roomId())).forEach(c -> send(c, "availability.changed")));
    }

    private void submit(Runnable task) {
        try { delivery.execute(task); } catch (RejectedExecutionException ignored) { /* bounded load; clients reconcile */ }
    }
    private void send(Client client, String type) {
        if (client == null) return;
        try {
            client.session().sendMessage(new TextMessage("{\"type\":\"" + type + "\",\"roomId\":" + client.roomId()
                    + ",\"signal\":\"" + UUID.randomUUID() + "\"}"));
        } catch (Exception ignored) { remove(client.session(), CloseStatus.SERVER_ERROR); }
    }
    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        submit(() -> clients.values().forEach(c -> {
            try { c.session().sendMessage(new PingMessage()); }
            catch (Exception ignored) { remove(c.session(), CloseStatus.SESSION_NOT_RELIABLE); }
        }));
    }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) { remove(session, CloseStatus.POLICY_VIOLATION); }
    @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) { remove(session, CloseStatus.POLICY_VIOLATION); }
    @Override public void handleTransportError(WebSocketSession session, Throwable error) { remove(session, CloseStatus.SERVER_ERROR); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { clients.remove(session.getId()); }
    private void remove(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
        try { session.close(status); } catch (IOException ignored) { }
    }
    public int connections() { return clients.size(); }
    @PreDestroy public void stop() {
        delivery.shutdownNow(); clients.values().forEach(c -> remove(c.session(), CloseStatus.GOING_AWAY));
    }
}
