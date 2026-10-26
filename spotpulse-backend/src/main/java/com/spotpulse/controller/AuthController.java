package com.spotpulse.controller;

import com.spotpulse.security.AccessPolicy;
import com.spotpulse.repository.UserRepository;
import com.spotpulse.mapper.EntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final AccessPolicy access;
    private final UserRepository users;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    @GetMapping("/api/auth/me")
    public Object me() {
        Long id = access.optionalUserId();
        var user = id == null ? java.util.Optional.<com.spotpulse.model.User>empty() : users.findById(id);
        return new SessionProfile(user.isPresent(), user.map(EntityMapper::toDto).orElse(null), registrations.getIfAvailable() != null);
    }
    public record SessionProfile(boolean authenticated, com.spotpulse.dto.UserDto user, boolean loginEnabled) { }
    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/oauth2/authorization/google")
    @ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable() { return Map.of("detail", "Google login is not configured in this server process"); }
}
