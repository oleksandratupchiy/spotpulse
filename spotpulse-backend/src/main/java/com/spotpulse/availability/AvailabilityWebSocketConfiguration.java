package com.spotpulse.availability;

import com.spotpulse.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.*;

@Configuration @EnableWebSocket @EnableScheduling
public class AvailabilityWebSocketConfiguration implements WebSocketConfigurer {
    private final AvailabilitySocket socket;
    private final RoomRepository rooms;
    private final String[] origins;
    public AvailabilityWebSocketConfiguration(AvailabilitySocket socket, RoomRepository rooms,
            @Value("${spotpulse.availability.allowed-origins:http://localhost:5173,http://localhost:8080}") String allowed) {
        this.socket = socket; this.rooms = rooms;
        origins = Arrays.stream(allowed.split(",")).map(String::strip).peek(origin -> {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty()))
                throw new IllegalArgumentException("Availability origins must be exact HTTP(S) origins without paths or wildcards");
        }).toArray(String[]::new);
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/api/rooms/availability/ws").addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Map<String, Object> attributes) {
                if (!Arrays.asList(origins).contains(request.getHeaders().getOrigin())) {
                    response.setStatusCode(HttpStatus.FORBIDDEN); return false;
                }
                try {
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    if (query.size() != 1 || query.get("roomId") == null || query.get("roomId").size() != 1) throw new IllegalArgumentException();
                    long room = Long.parseLong(query.getFirst("roomId"));
                    if (room < 1) throw new IllegalArgumentException();
                    if (!rooms.existsById(room)) { response.setStatusCode(HttpStatus.NOT_FOUND); return false; }
                    attributes.put("roomId", room); return true;
                } catch (IllegalArgumentException ignored) { response.setStatusCode(HttpStatus.BAD_REQUEST); return false; }
            }
            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception failure) { }
        }).setAllowedOrigins(origins);
    }
}
