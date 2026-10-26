package com.spotpulse.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Resolve the local default after config data, without opening/creating a database or directory. */
public class LocalDatabaseEnvironment implements EnvironmentPostProcessor, Ordered {
    public static final String GENERATED_URL = "spotpulse.local.generated-datasource-url";

    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty("spring.datasource.url")) {
            if (environment.getProperty("spring.datasource.url", "").isBlank())
                throw new IllegalStateException("An explicit datasource URL must not be blank");
            return;
        }
        if (!environment.acceptsProfiles(Profiles.of("local"))) return;
        String ddl = environment.getProperty("spring.jpa.hibernate.ddl-auto", "update").toLowerCase(Locale.ROOT);
        if (java.util.Set.of("create", "create-drop", "drop", "create-only").contains(ddl))
            throw new IllegalStateException("Refusing destructive schema generation for the persistent local default");
        Path directory = dataDirectory(environment);
        String url = "jdbc:h2:file:" + directory.resolve("spotpulse").toString().replace('\\', '/');
        environment.getPropertySources().addLast(new MapPropertySource("spotpulseLocalDatabaseDefault",
                Map.of("spring.datasource.url", url, GENERATED_URL, url)));
    }

    public static Path dataDirectory(Environment environment) {
        String configured = environment.getProperty("spotpulse.local.data-directory", "");
        String os = environment.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path directory;
        if (!configured.isBlank()) directory = absolute(configured);
        else if (os.startsWith("windows")) {
            String local = environment.getProperty("LOCALAPPDATA", "");
            directory = local.isBlank() ? home(environment).resolve("AppData/Local/SpotPulse") : absolute(local).resolve("SpotPulse");
        } else if (os.startsWith("mac")) directory = home(environment).resolve("Library/Application Support/SpotPulse");
        else {
            String xdg = environment.getProperty("XDG_DATA_HOME", "");
            directory = xdg.isBlank() ? home(environment).resolve(".local/share/SpotPulse") : absolute(xdg).resolve("SpotPulse");
        }
        directory = directory.normalize();
        // Resolve existing ancestors, including symlinks, without creating anything.
        Path resolved = canonical(directory);
        Path working = canonical(absolute(environment.getProperty("user.dir", ".")));
        Path repository = working;
        while (repository != null && !Files.exists(repository.resolve(".git"))) repository = repository.getParent();
        if (resolved.startsWith(repository == null ? working : repository))
            throw new IllegalStateException("Local database directory must be outside the checkout/working directory");
        if (resolved.toString().contains(";"))
            throw new IllegalStateException("Local database directory cannot contain H2 URL option delimiters");
        return resolved;
    }

    private static Path home(Environment environment) {
        return absolute(environment.getRequiredProperty("user.home"));
    }
    private static Path absolute(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalStateException("Application-data paths must be absolute");
        return path.normalize();
    }
    private static Path canonical(Path path) {
        try {
            Path existing = path;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null) throw new IllegalStateException("Application-data path has no existing absolute ancestor");
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot resolve application-data directory", failure);
        }
    }
}
