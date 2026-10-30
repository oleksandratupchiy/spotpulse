package com.spotpulse.persistence;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class LocalDatabasePersistenceTests {
    @Test void threeSeparateBackendProcessesReuseRecordsAndBootstrapAcrossRestarts() throws Exception {
        Path root = Files.createTempDirectory("spotpulse-persistence-").toRealPath();
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            for (String phase : new String[]{"write", "bootstrap", "verify"}) {
                Path log = root.resolve(phase + ".log");
                ProcessBuilder builder = new ProcessBuilder(java, "-Xmx256m", "-cp", classpath, LocalDatabaseProcessProbe.class.getName(), phase, root.toString());
                // Children do not use or retain real OAuth credentials or inherited datasource/bootstrap settings.
                for (String variable : new String[]{"GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SPRING_DATASOURCE_DRIVER_CLASS_NAME", "SPRING_JPA_DATABASE_PLATFORM", "SPRING_DATASOURCE_HIKARI_JDBC_URL", "SPRING_APPLICATION_JSON", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION", "SPRING_PROFILES_ACTIVE", "SPRING_PROFILES_DEFAULT", "SPOTPULSE_TEST_DATABASE_URL", "SPOTPULSE_BOOTSTRAP_ADMIN_ID"}) builder.environment().remove(variable);
                builder.redirectErrorStream(true).redirectOutput(log.toFile());
                Process process = builder.start();
                // Three cold Spring JVMs can be slow on Windows while other verification runs.
                if (!process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly(); process.waitFor(); fail("Persistence subprocess timed out: " + phase + "\n" + read(log));
                }
                assertThat(process.exitValue()).withFailMessage(() -> "Persistence phase failed: " + phase + "\n" + read(log)).isZero();
                assertThat(Files.readString(log)).contains("PERSISTENCE_PHASE_OK " + phase);
                assertThat(root.resolve("spotpulse.mv.db")).exists();
            }
            System.out.println("PERSISTENCE_RESTART_OK: three separate JVMs, two restarts; identities, all roles, ownership, room, booking and one-time bootstrap retained.");
        } finally {
            // Only the random directory created by this test may be recursively removed.
            if (!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                    || !root.getFileName().toString().startsWith("spotpulse-persistence-")) throw new IllegalStateException("Refusing unsafe test cleanup");
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static String read(Path log) {
        try { return Files.readString(log); } catch (java.io.IOException failure) { return "Cannot read subprocess log"; }
    }
}
