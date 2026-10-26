package com.spotpulse.cache;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component("readCaches")
public class ReadCaches {
    public static final List<String> NAMES = List.of("spaceDetails", "roomDetails", "autocomplete", "spaceMap", "bookingAnalytics");
    private final CacheManager manager;
    private final ReadCacheProperties properties;
    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    public ReadCaches(CacheManager manager, ReadCacheProperties properties) {
        this.manager = manager;
        this.properties = properties;
        NAMES.forEach(name -> revisions.put(name, new AtomicLong()));
    }

    public boolean allowed() {
        // Never reuse shared results inside an existing transaction, whose snapshot or
        // uncommitted changes may differ. Normal HTTP reads enter here before their transaction.
        return properties.enabled() && !TransactionSynchronizationManager.isActualTransactionActive();
    }

    public record Key(long revision, List<Object> parameters) { }

    public Key key(String name, Object... parameters) {
        return new Key(revisions.get(name).get(), Collections.unmodifiableList(Arrays.asList(parameters.clone())));
    }

    @TransactionalEventListener
    public void afterCommit(ReadDataChanged change) {
        List<String> affected = switch (change.kind()) {
            case SPACES -> NAMES;
            case ROOMS -> List.of("spaceDetails", "roomDetails", "bookingAnalytics");
            case BOOKINGS -> List.of("bookingAnalytics");
        };
        affected.forEach(name -> {
            // An older in-flight read can only populate its old generation, which no
            // subsequent request can address. Clearing alone would permit stale refill.
            revisions.get(name).incrementAndGet();
            manager.getCache(name).clear();
        });
    }
}
