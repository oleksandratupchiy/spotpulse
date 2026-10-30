package com.spotpulse.persistence;

import com.spotpulse.config.LocalDatabaseEnvironment;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/** Test-classpath-only guard, before Hibernate can run create-drop or fixtures can delete rows. */
public class TestDatabaseIsolation implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 2; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // All automated tests are offline, even if the launching shell has a real bot credential.
        environment.getPropertySources().addFirst(new MapPropertySource("offlineTelegramTests",
                Map.of("spotpulse.telegram.enabled", "false")));
        // Ordinary tests never select a real cloud provider, even if the launching shell does.
        // Dedicated Cloudinary tests use an explicit mocked operations bean and test-only dynamic properties.
        environment.getPropertySources().addFirst(new MapPropertySource("offlineImageProviderTests",
                Map.of("spotpulse.images.storage-provider", "local")));
        // No automated context may write to the developer's image directory.
        environment.getPropertySources().addFirst(new MapPropertySource("isolatedImageStorage",
                Map.of("spotpulse.images.directory", Path.of(System.getProperty("java.io.tmpdir"),
                        "spotpulse-images-test-" + UUID.randomUUID()).toString())));
        String url = environment.getProperty("spring.datasource.url", "");
        String poolUrl = environment.getProperty("spring.datasource.hikari.jdbc-url");
        if (poolUrl != null && !poolUrl.equals(url)) throw new IllegalStateException("Tests refuse a separate Hikari JDBC URL override");
        if (url.startsWith("jdbc:h2:mem:")) return;
        String temporaryRoot = environment.getProperty("spotpulse.test.file-root", "");
        if (url.startsWith("jdbc:h2:file:") && !temporaryRoot.isBlank()) {
            try {
                Path root = Path.of(temporaryRoot).toRealPath();
                Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
                Path database = Path.of(url.substring("jdbc:h2:file:".length()).split(";", 2)[0]).toAbsolutePath().normalize();
                database = Files.exists(database) ? database.toRealPath() : database.getParent().toRealPath().resolve(database.getFileName());
                if (root.getParent().equals(temp) && root.getFileName().toString().startsWith("spotpulse-persistence-") && database.startsWith(root)) return;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cannot verify isolated test database path", failure);
            }
            throw new IllegalStateException("File-based tests require their own isolated temporary database directory");
        }
        if (url.isBlank() || url.equals(environment.getProperty(LocalDatabaseEnvironment.GENERATED_URL))) {
            environment.getPropertySources().addFirst(new MapPropertySource("isolatedTestDatabaseDefault",
                    Map.of("spring.datasource.url", "jdbc:h2:mem:isolated-" + UUID.randomUUID())));
            return;
        }
        // Retain the existing explicit PostgreSQL integration-test opt-in, never the Docker app DB.
        if (url.startsWith("jdbc:postgresql:") && url.equals(environment.getProperty("SPOTPULSE_TEST_DATABASE_URL"))) {
            String database = url.substring(url.lastIndexOf('/') + 1).split("\\?", 2)[0];
            if (database.endsWith("_test")) return;
        }
        throw new IllegalStateException("Tests refuse non-isolated datasource URLs; use in-memory H2 or a dedicated PostgreSQL *_test database");
    }
}
