package com.spotpulse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTests {
    Map<String, Object> valid() {
        return new HashMap<>(Map.of("RENDER_EXTERNAL_HOSTNAME", "assigned-service.onrender.com",
                "DATABASE_URL", "postgresql://render_user:CANARY_DATABASE_PASSWORD@dpg-private/db_name",
                "GOOGLE_CLIENT_ID", "test-only.apps.googleusercontent.com", "GOOGLE_CLIENT_SECRET", "CANARY_GOOGLE_SECRET",
                "CLOUDINARY_CLOUD_NAME", "test-cloud", "CLOUDINARY_API_KEY", "123456789012345", "CLOUDINARY_API_SECRET", "CANARY_CLOUD_SECRET"));
    }
    ConfigurableEnvironment environment(Map<String, Object> values) throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new PropertiesPropertySource("prod", PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"))));
        env.getPropertySources().addFirst(new MapPropertySource("scenario", values));
        env.setActiveProfiles("prod"); return env;
    }
    void apply(ConfigurableEnvironment env) { new ProductionEnvironment().postProcessEnvironment(env, new SpringApplication()); }

    @Test void actualRenderUrlBecomesCredentialFreeJdbcWithSeparateCredentialsAndTls() throws Exception {
        var env = environment(valid()); apply(env);
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-private:5432/db_name?sslmode=require");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("render_user");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("CANARY_DATABASE_PASSWORD");
        assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("5");
        assertThat(env.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(env.getProperty("spring.h2.console.enabled")).isEqualTo("false");
    }
    @Test void assignedHostnameDerivesOneHttpsOriginCallbackAndWebsocketOrigin() throws Exception {
        var env = environment(valid()); apply(env);
        assertThat(env.getProperty("spotpulse.auth.frontend-origin")).isEqualTo("https://assigned-service.onrender.com");
        assertThat(env.getProperty("spotpulse.auth.redirect-uri")).isEqualTo("https://assigned-service.onrender.com/login/oauth2/code/google");
        assertThat(env.getProperty("spotpulse.availability.allowed-origins")).isEqualTo("https://assigned-service.onrender.com");
        assertThat(env.getProperty("spotpulse.seo.public-base-url")).isEqualTo("https://assigned-service.onrender.com");
        assertThat(env.getProperty("spotpulse.auth.ui-base-path")).isEqualTo("/app");
        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }
    @Test void customPublicOriginIsExplicitAndNoInventedHostnameIsRequired() throws Exception {
        var values = valid(); values.remove("RENDER_EXTERNAL_HOSTNAME"); values.put("SPOTPULSE_PUBLIC_BASE_URL", "https://coworking.example.org/");
        var env = environment(values); apply(env);
        assertThat(env.getProperty("spotpulse.auth.frontend-origin")).isEqualTo("https://coworking.example.org");
    }
    @Test void jdbcModeRequiresSeparateCredentialsAndNeverFallsBackToH2() throws Exception {
        var values = valid(); values.remove("DATABASE_URL");
        values.put("spring.datasource.url", "jdbc:postgresql://dpg-private:5432/db_name");
        values.put("SPRING_DATASOURCE_USERNAME", "render_user"); values.put("SPRING_DATASOURCE_PASSWORD", "CANARY_DATABASE_PASSWORD");
        var env = environment(values); apply(env);
        assertThat(env.getProperty("spring.datasource.url")).endsWith("?sslmode=require");
        values.remove("SPRING_DATASOURCE_PASSWORD");
        assertThatThrownBy(() -> apply(environment(values))).isInstanceOf(IllegalStateException.class);
        values.put("spring.datasource.url", "jdbc:h2:file:/developer/data");
        assertThatThrownBy(() -> apply(environment(values))).isInstanceOf(IllegalStateException.class);
    }
    @Test void percentEncodedPasswordsAndLiteralPlusAreDecodedWithoutEnteringJdbcUrl() throws Exception {
        var values = valid(); values.put("DATABASE_URL", "postgres://render_user:p%40ss%3Aword+plus@dpg-private:5444/db_name?sslmode=require");
        var env = environment(values); apply(env);
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p@ss:word+plus");
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-private:5444/db_name?sslmode=require");
    }
    @Test void everyRequiredConfigurationValueIsValidatedBeforeDatasourceStartup() throws Exception {
        for (String missing : List.of("RENDER_EXTERNAL_HOSTNAME", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "CLOUDINARY_CLOUD_NAME", "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET", "DATABASE_URL")) {
            var values = valid(); values.remove(missing);
            assertThatThrownBy(() -> apply(environment(values))).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
    }
    @Test void unsafeProductionOverridesAndMismatchedOriginsAreRejected() throws Exception {
        var overrides = Map.ofEntries(
                Map.entry("spring.jpa.hibernate.ddl-auto", "create-drop"), Map.entry("spring.h2.console.enabled", true),
                Map.entry("spring.jpa.show-sql", true), Map.entry("server.servlet.session.cookie.secure", false),
                Map.entry("server.servlet.session.cookie.http-only", false), Map.entry("server.servlet.session.cookie.same-site", "none"),
                Map.entry("spotpulse.images.storage-provider", "local"), Map.entry("debug", true), Map.entry("trace", true),
                Map.entry("logging.level.root", "DEBUG"), Map.entry("SPOTPULSE_FRONTEND_ORIGIN", "http://localhost:5173"),
                Map.entry("SPOTPULSE_OAUTH_REDIRECT_URI", "https://evil.example/callback"), Map.entry("SPOTPULSE_AVAILABILITY_ALLOWED_ORIGINS", "*"));
        for (var override : overrides.entrySet()) {
            var values = valid(); values.put(override.getKey(), override.getValue());
            assertThatThrownBy(() -> apply(environment(values))).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void unsafeDatabaseAndPublicUrlsFailWithoutEchoingCredentials() throws Exception {
        for (String url : List.of("https://user:CANARY_SECRET@evil.example/db", "postgres://u:CANARY_SECRET@host/db?sslmode=disable",
                "postgres://u:CANARY_SECRET@host/db?password=secret", "postgres://u:CANARY_SECRET@host/db#fragment", "not-a-url")) {
            var values = valid(); values.put("DATABASE_URL", url);
            assertThatThrownBy(() -> apply(environment(values))).isInstanceOfSatisfying(IllegalStateException.class,
                    error -> assertThat(error.getMessage()).doesNotContain("CANARY_SECRET", "CANARY_CLOUD_SECRET", url));
        }
        for (String url : List.of("http://assigned.onrender.com", "https://user:CANARY_SECRET@assigned.onrender.com", "https://assigned.onrender.com/app", "https://assigned.onrender.com?next=evil")) {
            var values = valid(); values.put("SPOTPULSE_PUBLIC_BASE_URL", url);
            assertThatThrownBy(() -> apply(environment(values))).isInstanceOfSatisfying(IllegalStateException.class,
                    error -> assertThat(error.getMessage()).doesNotContain("CANARY_SECRET", url));
        }
    }
    @Test void localModeIsUntouchedAndCombiningLocalWithProdIsRejected() throws Exception {
        var env = environment(Map.of()); env.setActiveProfiles("local"); apply(env);
        assertThat(env.getPropertySources().contains("spotpulseProduction")).isFalse();
        var mixed = environment(valid()); mixed.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> apply(mixed)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Do not combine");
    }
}
