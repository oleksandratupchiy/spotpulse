package com.spotpulse.premium;

import com.spotpulse.service.SpaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequiredArgsConstructor
public class PremiumController {
    private final FeatureFlags flags;
    private final SpaceService spaces;
    private final PremiumAnalytics analytics;
    public record Toggle(@NotNull Boolean enabled) { }

    @GetMapping("/api/admin/features")
    public Object flags() { return flags.list(); }
    @PutMapping("/api/admin/features/{key}")
    public Object update(@PathVariable String key, @Valid @RequestBody Toggle toggle) { return flags.update(key, toggle.enabled()); }
    @PutMapping("/api/admin/spaces/{id}/premium")
    public Object premium(@PathVariable Long id, @Valid @RequestBody Toggle toggle) { return spaces.setPremium(id, toggle.enabled()); }
    @GetMapping("/api/management/premium-features")
    public Object state() { return flags.state(); }
    @GetMapping("/api/analytics/spaces/{id}/room-performance")
    public RoomPerformanceDto performance(@PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.get(id, from, to);
    }
}
