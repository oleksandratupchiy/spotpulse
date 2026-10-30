package com.spotpulse.telegram;

import com.spotpulse.repository.UserRepository;
import com.spotpulse.security.AccessPolicy;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class TelegramLinkService {
    private final TelegramAccountRepository accounts;
    private final UserRepository users;
    private final AccessPolicy access;
    private final TelegramClient client;
    private final TelegramRuntime runtime;
    private final EntityManager entityManager;
    private final SecureRandom random = new SecureRandom();

    public record Status(boolean configured, boolean available, boolean connected, Instant expiresAt,
                         boolean bookingNotificationsEnabled) { }
    public record Link(String url, Instant expiresAt) { }

    @Transactional(readOnly = true) @PreAuthorize("isAuthenticated()")
    public Status status() {
        var account = accounts.findById(access.userId()).orElse(null);
        var bot = runtime.bot();
        return new Status(client.configured(), bot != null,
                account != null && account.getChatId() != null,
                account != null && account.getExpiresAt() != null && account.getExpiresAt().isAfter(Instant.now())
                        ? account.getExpiresAt() : null,
                account == null || account.bookingNotificationsEnabled());
    }

    @Transactional @PreAuthorize("isAuthenticated()")
    public Status preferences(boolean enabled) {
        var user = users.lockById(access.userId()).orElseThrow();
        var account = accounts.findById(user.getId()).orElseGet(() -> {
            var created = new TelegramAccount(); created.setUser(user); return created;
        });
        account.setBookingNotificationsEnabled(enabled);
        accounts.save(account);
        return status();
    }

    @Transactional @PreAuthorize("isAuthenticated()")
    public Link connect() {
        var bot = runtime.bot();
        if (!client.configured() || bot == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telegram is unavailable. Try again later.");
        var user = users.lockById(access.userId()).orElseThrow();
        var account = accounts.findById(user.getId()).orElseGet(() -> {
            var created = new TelegramAccount(); created.setUser(user); return created;
        });
        // Each new attempt replaces all outstanding codes. Existing delivery stops until relinked.
        account.setChatId(null); account.setLinkedAt(null); account.setBotId(bot.id());
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        account.setCodeHash(hash(code)); account.setExpiresAt(Instant.now().plusSeconds(300));
        accounts.save(account);
        return new Link("https://t.me/" + bot.username() + "?start=" + code, account.getExpiresAt());
    }

    @Transactional @PreAuthorize("isAuthenticated()")
    public void disconnect() {
        users.lockById(access.userId()).orElseThrow();
        accounts.findById(access.userId()).ifPresent(account -> {
            account.setChatId(null); account.setBotId(null); account.setLinkedAt(null); account.revokeCode();
        });
    }

    // Called only by the polling transaction, never from an HTTP request with user-supplied IDs.
    String accept(Long botId, Long chatId, String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{43}")) return "Open your SpotPulse profile and select Connect Telegram.";
        var candidate = accounts.findByCodeHash(hash(code)).orElse(null);
        if (candidate == null) return "This connection link is invalid or expired. Create a new link in SpotPulse.";
        users.lockById(candidate.getUserId()).orElseThrow();
        // A concurrent disconnect/replacement may have committed while waiting for the user lock.
        entityManager.refresh(candidate);
        if (!hash(code).equals(candidate.getCodeHash()) || !botId.equals(candidate.getBotId())
                || candidate.getExpiresAt() == null || !candidate.getExpiresAt().isAfter(Instant.now()))
            return "This connection link is invalid or expired. Create a new link in SpotPulse.";
        candidate.revokeCode();
        if (accounts.findByChatId(chatId).isPresent())
            return "This Telegram chat is already connected. Disconnect it from its SpotPulse profile first.";
        candidate.setChatId(chatId); candidate.setLinkedAt(Instant.now());
        accounts.flush(); // The unique chat constraint also protects concurrent cross-account links.
        return "Telegram connected to SpotPulse. You can disconnect from your SpotPulse profile.";
    }

    static String hash(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
