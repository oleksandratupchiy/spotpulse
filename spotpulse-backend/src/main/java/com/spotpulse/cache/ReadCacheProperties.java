package com.spotpulse.cache;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("spotpulse.cache")
public record ReadCacheProperties(boolean enabled, @NotNull Duration ttl,
                                   @Min(1) @Max(100000) long maximumSize) {
    @AssertTrue(message = "Cache TTL must be positive and at most one day")
    public boolean isTtlValid() {
        return ttl != null && !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(Duration.ofDays(1)) <= 0;
    }
}
