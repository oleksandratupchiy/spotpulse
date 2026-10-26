package com.spotpulse.persistence;

import com.spotpulse.config.LocalDatabaseEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class LocalDatabaseConfigurationTests {
    @TempDir Path temporary;
    @org.junit.jupiter.api.BeforeEach void canonicalTemporaryPath() throws Exception { temporary = temporary.toRealPath(); }

    ConfigurableEnvironment environment(Map<String, Object> values) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("scenario", values));
        environment.setDefaultProfiles("local");
        return environment;
    }
    void apply(ConfigurableEnvironment environment) { new LocalDatabaseEnvironment().postProcessEnvironment(environment, new SpringApplication()); }

    @Test void windowsLocalDefaultIsAbsoluteExternalAndDoesNotCreateFiles() throws Exception {
        Path work = temporary.resolve("checkout"); Files.createDirectories(work.resolve(".git"));
        Path appData = temporary.resolve("app-data");
        var environment = environment(Map.of("os.name", "Windows 11", "LOCALAPPDATA", appData.toString(), "user.dir", work.toString()));
        apply(environment);
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:file:" + appData.resolve("SpotPulse/spotpulse").toString().replace('\\', '/'));
        assertThat(Files.exists(appData)).isFalse();
    }

    @Test void platformDirectoriesAndAbsoluteOverrideAreStable() throws Exception {
        Path work = temporary.resolve("checkout"); Files.createDirectories(work);
        var linux = environment(Map.of("os.name", "Linux", "user.home", temporary.resolve("home").toString(), "user.dir", work.toString()));
        assertThat(LocalDatabaseEnvironment.dataDirectory(linux)).isEqualTo(temporary.resolve("home/.local/share/SpotPulse"));
        var xdg = environment(Map.of("os.name", "Linux", "XDG_DATA_HOME", temporary.resolve("xdg").toString(), "user.dir", work.toString()));
        assertThat(LocalDatabaseEnvironment.dataDirectory(xdg)).isEqualTo(temporary.resolve("xdg/SpotPulse"));
        var mac = environment(Map.of("os.name", "Mac OS X", "user.home", temporary.resolve("home").toString(), "user.dir", work.toString()));
        assertThat(LocalDatabaseEnvironment.dataDirectory(mac)).isEqualTo(temporary.resolve("home/Library/Application Support/SpotPulse"));
        var fallback = environment(Map.of("os.name", "Windows 11", "user.home", temporary.resolve("home").toString(), "user.dir", work.toString()));
        assertThat(LocalDatabaseEnvironment.dataDirectory(fallback)).isEqualTo(temporary.resolve("home/AppData/Local/SpotPulse"));
        var override = environment(Map.of("spotpulse.local.data-directory", temporary.resolve("custom").toString(), "user.dir", work.toString()));
        assertThat(LocalDatabaseEnvironment.dataDirectory(override)).isEqualTo(temporary.resolve("custom"));
    }

    @Test void explicitUrlsWinEvenWhenLocalDirectoryWouldBeInvalidAndOtherProfilesAreUnaffected() {
        for (String url : new String[]{"jdbc:h2:mem:explicit", "jdbc:postgresql://localhost/spotpulse_db"}) {
            var environment = environment(Map.of("spring.datasource.url", url, "spotpulse.local.data-directory", "relative-invalid"));
            apply(environment);
            assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(url);
            assertThat(environment.getPropertySources().contains("spotpulseLocalDatabaseDefault")).isFalse();
        }
        var production = environment(Map.of()); production.setActiveProfiles("production"); apply(production);
        assertThat(production.getProperty("spring.datasource.url")).isNull();
    }

    @Test void relativeCheckoutAndUrlOptionPathsAreRejected() throws Exception {
        Path work = temporary.resolve("checkout"); Files.createDirectories(work.resolve(".git"));
        for (String directory : new String[]{"./data", work.resolve("data").toString(), temporary.resolve("data;INIT=bad").toString()}) {
            var environment = environment(Map.of("spotpulse.local.data-directory", directory, "user.dir", work.toString()));
            assertThatThrownBy(() -> apply(environment)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void emptyExplicitUrlAndDestructiveSchemaDefaultsFailBeforeAnyDatabaseIsOpened() {
        assertThatThrownBy(() -> apply(environment(Map.of("spring.datasource.url", "")))).isInstanceOf(IllegalStateException.class);
        for (String ddl : new String[]{"create", "create-drop", "drop"})
            assertThatThrownBy(() -> apply(environment(Map.of("spring.jpa.hibernate.ddl-auto", ddl)))).isInstanceOf(IllegalStateException.class);
    }

    @Test void testClasspathReplacesGeneratedLocalDefaultAndRejectsExplicitRealDatabasesBeforeStartup() throws Exception {
        Path work = temporary.resolve("checkout"); Files.createDirectories(work);
        var environment = environment(Map.of("spotpulse.local.data-directory", temporary.resolve("developer-data").toString(), "user.dir", work.toString()));
        apply(environment);
        new TestDatabaseIsolation().postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:isolated-");
        for (String url : new String[]{"jdbc:h2:file:" + temporary.resolve("existing-user-db"), "jdbc:postgresql://localhost/spotpulse_db"}) {
            var unsafe = environment(Map.of("spring.datasource.url", url));
            assertThatThrownBy(() -> new TestDatabaseIsolation().postProcessEnvironment(unsafe, new SpringApplication())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(Files.exists(temporary.resolve("existing-user-db.mv.db"))).isFalse();
    }
}
