package com.spotpulse.availability;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionalEventListener;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: its name intentionally does not match Surefire's ordinary test patterns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:c10-experiment;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@Import(C10ComparisonExperiment.ClockConfiguration.class)
class C10ComparisonExperiment extends AvailabilityTestSupport {
    static class Trial {
        final int index; final boolean occupied; final CountDownLatch observed;
        final AtomicLong start = new AtomicLong();
        final Set<Integer> seen = ConcurrentHashMap.newKeySet();
        Trial(int index, boolean occupied, int clients) { this.index = index; this.occupied = occupied; observed = new CountDownLatch(clients); }
    }
    static class CommitClock {
        volatile Long room; volatile Trial trial;
        @Order(Ordered.HIGHEST_PRECEDENCE) @TransactionalEventListener
        public void committed(AvailabilityChanged event) {
            Trial current = trial;
            if (event.roomId().equals(room) && current != null) current.start.compareAndSet(0, System.nanoTime());
        }
    }
    @TestConfiguration static class ClockConfiguration { @Bean CommitClock commitClock() { return new CommitClock(); } }
    @Autowired CommitClock clock;
    record Sample(String mode, int clients, int trial, int client, Double notificationMs, double refreshMs) { }
    record Summary(String mode, int clients, int trials, int intervalMs, int samples, double medianMs, double p95Ms,
                   Double medianNotificationMs, Double p95NotificationMs, long httpRequests, long wsMessages, long payloadBytes,
                   int handshakes, Double medianHandshakeReadyMs, long idleRequests, long idleMessages, long idleMs, double recoveryMs) { }
    final List<Sample> samples = new CopyOnWriteArrayList<>();
    final AtomicLong requests = new AtomicLong(), bytes = new AtomicLong();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    Long activeBooking;

    @Test void compareBothTransports() throws Exception {
        int trials = Integer.getInteger("c10.trials", 10), interval = Integer.getInteger("c10.pollMs", 3000);
        int[] counts = Arrays.stream(System.getProperty("c10.clients", "1,5").split(",")).mapToInt(Integer::parseInt).toArray();
        assertThat(trials).isBetween(5, 30); assertThat(interval).isBetween(1000, 10000);
        assertThat(counts.length).isBetween(1, 3); for (int count : counts) assertThat(count).isBetween(1, 10);
        Path output = Path.of("target", "c10-benchmark", java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
        Files.createDirectories(output);
        fixture(); clock.room = room;
        var results = new ArrayList<Summary>();
        try {
            // Warm the shared REST and booking code before measurement. No transport measurements are fabricated.
            countedRead(); activeBooking = create().getId(); bookings.cancel(activeBooking); activeBooking = null;
            for (int clients : counts) for (String mode : List.of("websocket", "polling"))
                results.add(run(mode, clients, trials, interval));
        } finally {
            write(output, results, interval, trials);
            cleanup();
        }
        System.out.println("C10_MEASUREMENTS " + output.toAbsolutePath());
        results.forEach(System.out::println);
    }
    String countedRead() throws Exception {
        var response = snapshot(room); requests.incrementAndGet(); bytes.addAndGet(response.body().getBytes(StandardCharsets.UTF_8).length);
        assertThat(response.statusCode()).isEqualTo(200); return response.body();
    }
    boolean matches(String response, boolean occupied) { return response.contains("\"start\"") == occupied; }
    void mutate() {
        asActor();
        if (activeBooking == null) activeBooking = create().getId();
        else { bookings.cancel(activeBooking); activeBooking = null; }
    }
    Summary run(String mode, int clients, int trials, int interval) throws Exception {
        if (activeBooking != null) { bookings.cancel(activeBooking); activeBooking = null; }
        requests.set(0); bytes.set(0); failure.set(null); clock.trial = null;
        var connections = new ArrayList<SocketClient>(); var handshakes = new ArrayList<Double>();
        var pollers = Executors.newScheduledThreadPool(clients);
        var readers = Executors.newFixedThreadPool(clients);
        try {
            for (int i = 0; i < clients; i++) {
                if (mode.equals("websocket")) {
                    long begin = System.nanoTime(); connections.add(connect(room)); handshakes.add((System.nanoTime() - begin) / 1e6);
                }
                countedRead();
            }
            if (mode.equals("polling")) for (int i = 0; i < clients; i++) {
                int client = i;
                pollers.scheduleWithFixedDelay(() -> {
                    try {
                        String response = countedRead(); long observed = System.nanoTime(); Trial trial = clock.trial;
                        if (trial != null && trial.start.get() > 0 && matches(response, trial.occupied) && trial.seen.add(client)) {
                            double latency = (observed - trial.start.get()) / 1e6;
                            if (latency < 0) throw new AssertionError("Read preceded the after-commit marker; ambiguous sample");
                            samples.add(new Sample(mode, clients, trial.index, client, null, latency)); trial.observed.countDown();
                        }
                    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                }, interval * (i + 1L) / clients, interval, TimeUnit.MILLISECONDS);
            }
            long idleRequestsBefore = requests.get(), idleMessagesBefore = messages(connections);
            long idleMs = 2L * interval + 100;
            Thread.sleep(idleMs);
            long idleRequests = requests.get() - idleRequestsBefore, idleMessages = messages(connections) - idleMessagesBefore;
            var jitter = new Random(1024); // identical deterministic mutation gaps for both transports
            for (int index = 1; index <= trials; index++) {
                Thread.sleep(jitter.nextInt(interval));
                Trial trial = new Trial(index, activeBooking == null, clients); clock.trial = trial;
                if (mode.equals("websocket")) for (int i = 0; i < clients; i++) {
                    int client = i; SocketClient connection = connections.get(i);
                    readers.submit(() -> {
                        try {
                            Received message = connection.take();
                            assertThat(message.json()).contains("availability.changed");
                            double notification = (message.at() - trial.start.get()) / 1e6;
                            String response = countedRead(); long observed = System.nanoTime();
                            assertThat(matches(response, trial.occupied)).isTrue(); assertThat(notification).isGreaterThanOrEqualTo(0);
                            samples.add(new Sample(mode, clients, trial.index, client, notification, (observed - trial.start.get()) / 1e6));
                        } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                        finally { trial.observed.countDown(); }
                    });
                }
                mutate();
                assertThat(trial.start.get()).as("after-commit marker").isPositive();
                assertThat(trial.observed.await(45, TimeUnit.SECONDS)).as("all clients must observe trial " + index).isTrue();
                if (failure.get() != null) throw new AssertionError("Client measurement failed", failure.get());
            }
            clock.trial = null; pollers.shutdown(); assertThat(pollers.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
            long steadyRequests = requests.get(), steadyBytes = bytes.get() + connections.stream().mapToLong(c -> c.receivedBytes.get()).sum();
            long steadyMessages = messages(connections);
            connections.forEach(SocketClient::close); connections.clear();
            // Recovery is a separate one-client probe: miss a committed change, remain offline 1s, reconnect and reread.
            Trial recovery = new Trial(0, activeBooking == null, 1); clock.trial = recovery; mutate();
            Thread.sleep(1000);
            if (mode.equals("websocket")) connections.add(connect(room));
            assertThat(matches(countedRead(), recovery.occupied)).isTrue();
            double recoveryMs = (System.nanoTime() - recovery.start.get()) / 1e6;
            var selected = samples.stream().filter(s -> s.mode().equals(mode) && s.clients() == clients).toList();
            assertThat(selected).hasSize(trials * clients);
            var updates = selected.stream().map(Sample::refreshMs).toList();
            var notifications = selected.stream().map(Sample::notificationMs).filter(Objects::nonNull).toList();
            return new Summary(mode, clients, trials, interval, selected.size(), percentile(updates, .5), percentile(updates, .95),
                    notifications.isEmpty() ? null : percentile(notifications, .5), notifications.isEmpty() ? null : percentile(notifications, .95),
                    steadyRequests, steadyMessages, steadyBytes, handshakes.size(), handshakes.isEmpty() ? null : percentile(handshakes, .5),
                    idleRequests, idleMessages, idleMs, recoveryMs);
        } finally {
            clock.trial = null; pollers.shutdownNow(); readers.shutdownNow(); connections.forEach(SocketClient::close);
        }
    }
    long messages(List<SocketClient> clients) { return clients.stream().mapToLong(c -> c.receivedMessages.get()).sum(); }
    static double percentile(List<Double> samples, double percentile) {
        var sorted = samples.stream().sorted().toList(); return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1));
    }
    void write(Path output, List<Summary> results, int interval, int trials) throws Exception {
        var raw = new StringBuilder("mode,clients,trial,client,notification_ms,authoritative_refresh_ms\n");
        for (var s : samples) raw.append(s.mode()).append(',').append(s.clients()).append(',').append(s.trial()).append(',').append(s.client()).append(',').append(s.notificationMs() == null ? "" : s.notificationMs()).append(',').append(s.refreshMs()).append('\n');
        Files.writeString(output.resolve("trials.csv"), raw);
        var csv = new StringBuilder("mode,clients,trials,poll_interval_ms,samples,median_refresh_ms,p95_refresh_ms,median_notification_ms,p95_notification_ms,http_requests,ws_messages,received_payload_bytes,handshakes,median_handshake_ready_ms,idle_http_requests,idle_ws_messages,idle_ms,recovery_ms\n");
        var md = new StringBuilder("# Measured C10 comparison\n\nAll timings share System.nanoTime() in one JVM. Start: first after-commit listener; end: client notification receipt or authoritative HTTP response completion. Received payload bytes exclude framing, headers, ping/pong, TLS and TCP. Steady totals include initialization and idle; recovery is separate and includes a deliberate 1000ms outage.\n\n| Mode | Clients | Samples | Median refresh ms | p95 refresh ms | Median notification ms | HTTP GETs | WS messages | Received payload bytes | Idle GETs | Recovery ms |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var s : results) {
            csv.append(String.join(",", s.mode(), ""+s.clients(), ""+s.trials(), ""+s.intervalMs(), ""+s.samples(), ""+s.medianMs(), ""+s.p95Ms(), Objects.toString(s.medianNotificationMs(), ""), Objects.toString(s.p95NotificationMs(), ""), ""+s.httpRequests(), ""+s.wsMessages(), ""+s.payloadBytes(), ""+s.handshakes(), Objects.toString(s.medianHandshakeReadyMs(), ""), ""+s.idleRequests(), ""+s.idleMessages(), ""+s.idleMs(), ""+s.recoveryMs())).append('\n');
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %.2f | %.2f | %s | %d | %d | %d | %d | %.2f |%n", s.mode(), s.clients(), s.samples(), s.medianMs(), s.p95Ms(), s.medianNotificationMs() == null ? "n/a" : String.format(Locale.ROOT, "%.2f", s.medianNotificationMs()), s.httpRequests(), s.wsMessages(), s.payloadBytes(), s.idleRequests(), s.recoveryMs()));
        }
        Files.writeString(output.resolve("summary.csv"), csv); Files.writeString(output.resolve("summary.md"), md);
        var environment = new Properties();
        for (String key : List.of("os.name", "os.version", "os.arch", "java.version")) environment.setProperty(key, System.getProperty(key));
        environment.setProperty("logicalProcessors", "" + Runtime.getRuntime().availableProcessors());
        environment.setProperty("processor", Objects.toString(System.getenv("PROCESSOR_IDENTIFIER"), "unavailable"));
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
            environment.setProperty("physicalMemoryBytes", "" + os.getTotalMemorySize());
        environment.setProperty("pollIntervalMs", "" + interval); environment.setProperty("trialsPerCohort", "" + trials);
        environment.setProperty("database", "isolated in-memory H2; one embedded Tomcat; HTTP/1.1 loopback");
        try (var writer = Files.newBufferedWriter(output.resolve("environment.properties"))) { environment.store(writer, "C10 measured environment; no identities or credentials"); }
    }
}
