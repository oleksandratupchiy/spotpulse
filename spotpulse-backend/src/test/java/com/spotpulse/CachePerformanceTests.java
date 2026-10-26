package com.spotpulse;

import com.spotpulse.dto.*;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reproducible service-boundary measurements. Fixtures exist only in this isolated H2 database. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cache-performance;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.h2.console.enabled=false"
})
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class CachePerformanceTests {
    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired UserService users;
    @Autowired BookingService bookings;
    @Autowired BookingAnalyticsService analytics;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired CacheManager manager;
    @Autowired ObjectMapper json;

    public record Measurement(int requests, double meanMs, double medianMs, double p95Ms, long sqlStatements) { }

    Measurement measure(int requests, Runnable read) {
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        long[] samples = new long[requests];
        for (int i = 0; i < requests; i++) {
            long started = System.nanoTime(); read.run(); samples[i] = System.nanoTime() - started;
        }
        long sql = statistics.getPrepareStatementCount() - before;
        Arrays.sort(samples);
        return new Measurement(requests, Arrays.stream(samples).average().orElseThrow() / 1_000_000.0,
                samples[requests / 2] / 1_000_000.0,
                samples[(int) Math.ceil(requests * .95) - 1] / 1_000_000.0, sql);
    }

    @Test
    void measureUncachedColdWarmAndPostInvalidationReads() throws Exception {
        Long space = spaces.create(new SpaceCreateDto("Benchmark test hub", null, "Test address", "Kyiv", null)).getId();
        Long room = rooms.create(new RoomCreateDto("Benchmark test room", 4, BigDecimal.TEN, space)).getId();
        Long user = users.create(new UserCreateDto("Benchmark test user", "benchmark@example.test", null)).getId();
        LocalDate from = LocalDate.now().plusDays(1);
        LocalDate to = from.plusDays(29);
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 1200; i++) {
            var start = from.plusDays(i / 40).atStartOfDay().plusMinutes((i % 40) * 30L);
            rows.add(new Object[]{start, start.plusMinutes(30), i % 6 == 0 ? "CANCELLED" : "CONFIRMED", room, user});
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.batchUpdate(
                "insert into bookings (start_time,end_time,status,room_id,user_id) values (?,?,?,?,?)", rows));

        var uncachedTransaction = new TransactionTemplate(transactions);
        uncachedTransaction.setReadOnly(true);
        uncachedTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // Existing transactions deliberately bypass shared caches: same production service,
        // same SQL, same isolation, with a transaction begun by this harness instead of the advice.
        Runnable uncachedRead = () -> uncachedTransaction.execute(status -> analytics.getBookings(from, to));
        for (int i = 0; i < 10; i++) uncachedRead.run(); // JIT, query plan and H2 buffer warmup
        Measurement uncached = measure(50, uncachedRead);
        var cache = (CaffeineCache) manager.getCache("bookingAnalytics");
        cache.clear();
        Runnable cachedRead = () -> analytics.getBookings(from, to);
        Measurement cold = measure(1, cachedRead);
        Measurement warm = measure(200, cachedRead);
        assertThat(analytics.getBookings(from, to).total()).isEqualTo(1200);

        var extra = from.atTime(20, 0);
        bookings.create(new BookingCreateDto(room, user, extra, extra.plusHours(1), BookingStatus.CONFIRMED));
        Measurement invalidated = measure(1, cachedRead);
        assertThat(analytics.getBookings(from, to).total()).isEqualTo(1201);
        assertThat(uncached.sqlStatements()).isEqualTo(100);
        assertThat(cold.sqlStatements()).isEqualTo(2);
        assertThat(warm.sqlStatements()).isZero();
        assertThat(invalidated.sqlStatements()).isEqualTo(2);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetBookings", 1200); report.put("rangeDays", 30); report.put("warmupReads", 10);
        report.put("methodology", "Sequential service calls; H2; repeatable-read; uncached reads bypass cache inside explicit transactions; warm reads use normal service proxy. Excludes HTTP/serialization. No latency assertions.");
        report.put("uncached", uncached); report.put("cold", cold); report.put("warm", warm); report.put("afterInvalidation", invalidated);
        report.put("cacheHits", cache.getNativeCache().stats().hitCount());
        report.put("cacheMisses", cache.getNativeCache().stats().missCount());
        String output = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/B9-cache-benchmark.json"), output);
        System.out.println("B9_CACHE_BENCHMARK " + output);
    }
}
