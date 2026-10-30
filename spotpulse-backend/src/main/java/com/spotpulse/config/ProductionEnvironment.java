package com.spotpulse.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fail closed before Hibernate starts. Never include input values/causes in configuration failures. */
public class ProductionEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.acceptsProfiles(Profiles.of("prod"))) return;
        if (Arrays.asList(env.getActiveProfiles()).contains("local")) fail("Do not combine prod and local profiles");
        String origin = publicOrigin(env);
        sameIfSet(env, "SPOTPULSE_FRONTEND_ORIGIN", origin);
        sameIfSet(env, "SPOTPULSE_OAUTH_REDIRECT_URI", origin + "/login/oauth2/code/google");
        sameIfSet(env, "SPOTPULSE_AVAILABILITY_ALLOWED_ORIGINS", origin);
        for (String name : List.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "CLOUDINARY_CLOUD_NAME", "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET")) required(env, name);
        if (!"cloudinary".equals(env.getProperty("spotpulse.images.storage-provider", "cloudinary"))) fail("Production image storage must be cloudinary");
        if (!Set.of("update", "validate", "none").contains(env.getProperty("spring.jpa.hibernate.ddl-auto", "update"))) fail("Production schema mode must be update, validate or none");
        if (env.getProperty("spring.h2.console.enabled", Boolean.class, false) || env.getProperty("spring.jpa.show-sql", Boolean.class, false)) fail("Production H2 console and SQL logging must be disabled");
        if (env.getProperty("debug", Boolean.class, false) || env.getProperty("trace", Boolean.class, false)
                || Set.of("DEBUG", "TRACE").contains(env.getProperty("logging.level.root", "INFO").toUpperCase(Locale.ROOT))) fail("Production debug and trace logging must be disabled");
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)
                || !env.getProperty("server.servlet.session.cookie.http-only", Boolean.class, true)
                || !"lax".equalsIgnoreCase(env.getProperty("server.servlet.session.cookie.same-site", "lax"))) fail("Production sessions require Secure, HttpOnly, SameSite=Lax cookies");
        String bootstrap = env.getProperty("SPOTPULSE_BOOTSTRAP_ADMIN_ID", "");
        if (!bootstrap.isBlank()) {
            try { if (Long.parseLong(bootstrap) < 1) fail("Bootstrap ID must identify an existing verified account"); }
            catch (NumberFormatException ignored) { fail("Bootstrap ID must identify an existing verified account"); }
        }
        Map<String, Object> resolved = database(env);
        resolved.put("spotpulse.auth.frontend-origin", origin);
        resolved.put("spotpulse.auth.redirect-uri", origin + "/login/oauth2/code/google");
        resolved.put("spotpulse.auth.ui-base-path", "/app");
        resolved.put("spotpulse.availability.allowed-origins", origin);
        resolved.put("spotpulse.seo.public-base-url", origin);
        resolved.put("server.forward-headers-strategy", "native");
        resolved.put("server.tomcat.remoteip.protocol-header", "x-forwarded-proto");
        env.getPropertySources().addFirst(new MapPropertySource("spotpulseProduction", resolved));
    }

    static String publicOrigin(Environment env) {
        String configured = env.getProperty("SPOTPULSE_PUBLIC_BASE_URL", "");
        if (configured.isBlank()) {
            String hostname = required(env, "RENDER_EXTERNAL_HOSTNAME");
            if (!hostname.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) fail("Invalid Render public hostname");
            configured = "https://" + hostname;
        }
        try {
            URI uri = URI.create(configured);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().contains(".")
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            return "https://" + uri.getHost();
        } catch (IllegalArgumentException ignored) { throw new IllegalStateException("Production public URL must be a single HTTPS origin without credentials or paths"); }
    }
    private static void sameIfSet(Environment env, String key, String expected) {
        String value = env.getProperty(key, "");
        if (!value.isBlank() && !value.equals(expected)) fail("Production origin/callback settings must match the configured public HTTPS origin");
    }
    private static Map<String, Object> database(Environment env) {
        String render = env.getProperty("DATABASE_URL", "");
        String jdbc = env.getProperty("spring.datasource.url", "");
        if (!render.isBlank() && !jdbc.isBlank()) fail("Configure DATABASE_URL or a PostgreSQL JDBC URL, not both");
        String username, password;
        URI uri;
        try {
            if (!render.isBlank()) {
                uri = URI.create(render);
                if (!("postgres".equals(uri.getScheme()) || "postgresql".equals(uri.getScheme()))) throw new IllegalArgumentException();
                String userInfo = uri.getRawUserInfo(); int separator = userInfo == null ? -1 : userInfo.indexOf(':');
                if (separator < 1) throw new IllegalArgumentException();
                username = decode(userInfo.substring(0, separator)); password = decode(userInfo.substring(separator + 1));
            } else {
                if (!jdbc.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
                uri = URI.create(jdbc.substring(5));
                if (uri.getUserInfo() != null) throw new IllegalArgumentException();
                username = required(env, "SPRING_DATASOURCE_USERNAME"); password = required(env, "SPRING_DATASOURCE_PASSWORD");
            }
            if (uri.getHost() == null || uri.getFragment() != null || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_-]+")
                    || username.isBlank() || password.isBlank() || username.chars().anyMatch(Character::isISOControl)
                    || password.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            String query = uri.getRawQuery();
            if (query != null && !query.equals("sslmode=require")) throw new IllegalArgumentException();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String host = uri.getHost();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            return new HashMap<>(Map.of("spring.datasource.url", "jdbc:postgresql://" + host + ":" + port + uri.getPath() + "?sslmode=require",
                    "spring.datasource.username", username, "spring.datasource.password", password,
                    "spring.datasource.driver-class-name", "org.postgresql.Driver"));
        } catch (IllegalArgumentException ignored) { throw new IllegalStateException("Production requires a valid Render DATABASE_URL or PostgreSQL JDBC URL with separate credentials and TLS"); }
    }
    private static String decode(String raw) { return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8); }
    private static String required(Environment env, String name) {
        String value = env.getProperty(name, "");
        if (value.isBlank()) fail("Production configuration requires " + name);
        return value;
    }
    private static void fail(String message) { throw new IllegalStateException(message); }
}
