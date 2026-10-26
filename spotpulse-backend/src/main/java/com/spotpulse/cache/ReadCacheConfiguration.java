package com.spotpulse.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadCacheProperties.class)
// Cache advice surrounds transactions: misses are stored only after the read commits.
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 10)
public class ReadCacheConfiguration {
    @Bean
    @ConditionalOnMissingBean(Ticker.class)
    Ticker cacheTicker() { return Ticker.systemTicker(); }

    @Bean
    CacheManager cacheManager(ReadCacheProperties properties, Ticker ticker) {
        var manager = new SimpleCacheManager();
        manager.setCaches(ReadCaches.NAMES.stream().map(name -> new SnapshotCaffeineCache(name,
                Caffeine.newBuilder().maximumSize(properties.maximumSize()).expireAfterWrite(properties.ttl())
                        .ticker(ticker).recordStats().build())).toList());
        return manager;
    }
}
