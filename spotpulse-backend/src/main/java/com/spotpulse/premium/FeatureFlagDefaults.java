package com.spotpulse.premium;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor
public class FeatureFlagDefaults implements ApplicationRunner {
    private final FeatureFlagRepository repository;
    @Override @Transactional
    public void run(ApplicationArguments arguments) {
        for (var key : FeatureKey.values()) {
            if (!repository.existsById(key)) repository.save(new FeatureFlag(key, false));
        }
    }
}
