package com.spotpulse.premium;

import com.spotpulse.cache.ReadDataChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class FeatureFlags {
    private final FeatureFlagRepository repository;
    private final ApplicationEventPublisher events;

    public record Flag(FeatureKey key, String description, boolean enabled) { }
    public record State(boolean premium, boolean featured, boolean analytics, boolean badges) { }

    // Read the database on every request; privileges never depend on a cached flag.
    public State state() {
        var enabled = EnumSet.noneOf(FeatureKey.class);
        repository.findAll().stream().filter(FeatureFlag::isEnabled).forEach(f -> enabled.add(f.getKey()));
        boolean master = enabled.contains(FeatureKey.PREMIUM_FEATURES_ENABLED);
        return new State(master, master && enabled.contains(FeatureKey.FEATURED_SPACES_ENABLED),
                master && enabled.contains(FeatureKey.ADVANCED_ANALYTICS_ENABLED),
                master && enabled.contains(FeatureKey.PREMIUM_BADGES_ENABLED));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<Flag> list() {
        var values = new EnumMap<FeatureKey, Boolean>(FeatureKey.class);
        repository.findAll().forEach(f -> values.put(f.getKey(), f.isEnabled()));
        return Arrays.stream(FeatureKey.values()).map(k -> new Flag(k, k.description, values.getOrDefault(k, false))).toList();
    }

    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public Flag update(String key, boolean enabled) {
        FeatureKey supported;
        try { supported = FeatureKey.valueOf(key); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown feature flag"); }
        var flag = repository.findById(supported).orElseGet(() -> new FeatureFlag(supported, false));
        flag.setEnabled(enabled);
        repository.save(flag);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.SPACES));
        return new Flag(supported, supported.description, enabled);
    }
}
