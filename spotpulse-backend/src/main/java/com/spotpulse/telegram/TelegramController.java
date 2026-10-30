package com.spotpulse.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/account/telegram") @RequiredArgsConstructor
public class TelegramController {
    private final TelegramLinkService links;
    public record Preferences(@jakarta.validation.constraints.NotNull Boolean bookingNotificationsEnabled) { }
    @PatchMapping("/preferences") public ResponseEntity<TelegramLinkService.Status> preferences(
            @jakarta.validation.Valid @RequestBody Preferences preferences) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(links.preferences(preferences.bookingNotificationsEnabled()));
    }
    @GetMapping public ResponseEntity<TelegramLinkService.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(links.status());
    }
    @PostMapping("/connect") public ResponseEntity<TelegramLinkService.Link> connect() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(links.connect());
    }
    @DeleteMapping public ResponseEntity<Void> disconnect() {
        links.disconnect(); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
