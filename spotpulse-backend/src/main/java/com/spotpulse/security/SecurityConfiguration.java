package com.spotpulse.security;

import com.spotpulse.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import java.net.URI;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    org.springframework.security.core.userdetails.UserDetailsService passwordAuthenticationDisabled() {
        return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Password login is disabled"); };
    }
    static class GoogleConfigured implements Condition {
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return org.springframework.util.StringUtils.hasText(env.getProperty("GOOGLE_CLIENT_ID"))
                    && org.springframework.util.StringUtils.hasText(env.getProperty("GOOGLE_CLIENT_SECRET"));
        }
    }

    @Bean @Conditional(GoogleConfigured.class)
    ClientRegistrationRepository googleRegistration(
            @Value("${GOOGLE_CLIENT_ID}") String clientId, @Value("${GOOGLE_CLIENT_SECRET}") String secret,
            @Value("${spotpulse.auth.redirect-uri}") String redirectUri) {
        validateUrl(redirectUri, true);
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId).clientSecret(secret).scope("openid", "email", "profile")
                .redirectUri(redirectUri).build());
    }

    static String validateUrl(String value, boolean callback) {
        URI uri = URI.create(value);
        boolean local = "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost());
        if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (!callback && uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Authentication URLs must be trusted HTTPS origins or localhost HTTP URLs");
        }
        return value.replaceAll("/+$", "");
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> registrations,
                                 GoogleIdentityService identities, UserRepository users,
                                 @Value("${spotpulse.auth.frontend-origin}") String configuredOrigin,
                                 @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) throws Exception {
        String origin = validateUrl(configuredOrigin, false);
        if (origin.startsWith("https://") && !secureCookies)
            throw new IllegalArgumentException("HTTPS authentication requires secure session cookies");
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/h2-console/**").denyAll()
                .requestMatchers("/api/auth/me", "/api/auth/csrf", "/oauth2/**", "/login/**", "/error").permitAll()
                .requestMatchers("/api/users/**", "/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/analytics/**", "/api/management/**", "/spaces/new", "/spaces/*/edit").hasAnyRole("OWNER", "ADMIN")
                .requestMatchers("/api/bookings/**").authenticated()
                .requestMatchers("/api/account/telegram", "/api/account/telegram/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/spaces", "/api/spaces/**", "/api/rooms", "/api/rooms/**",
                        "/spaces", "/spaces/*", "/robots.txt", "/sitemap.xml", "/sitemaps/**", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/api/spaces", "/api/spaces/**", "/api/rooms", "/api/rooms/**",
                        "/spaces", "/spaces/*", "/robots.txt", "/sitemap.xml", "/sitemaps/**", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/spaces", "/api/spaces/**", "/api/rooms", "/api/rooms/**", "/spaces", "/spaces/**").hasAnyRole("OWNER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/spaces/**", "/api/rooms/**").hasAnyRole("OWNER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/spaces/**", "/api/rooms/**").hasAnyRole("OWNER", "ADMIN")
                .anyRequest().denyAll());
        // Default session CSRF repository + masked token support also protects Thymeleaf forms.
        http.csrf(csrf -> {});
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) -> problem(response, 401, "Sign in to continue"))
                .accessDeniedHandler((request, response, failure) -> problem(response, 403, "Access denied or CSRF token missing/invalid")));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)));
        var registration = registrations.getIfAvailable();
        if (registration != null) {
            var delegate = new OidcUserService();
            http.oauth2Login(oauth -> oauth.clientRegistrationRepository(registration)
                    .userInfoEndpoint(info -> info.oidcUserService(request -> identities.resolve(delegate.loadUser(request))))
                    .successHandler((request, response, auth) -> response.sendRedirect(origin + "/spaces"))
                    .failureHandler((request, response, failure) -> {
                        String code = failure instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauthFailure
                                && "account_migration_required".equals(oauthFailure.getError().getErrorCode()) ? "account_migration_required" : "login_failed";
                        response.sendRedirect(origin + "/login?error=" + code);
                    }));
        }
        http.addFilterAfter(new FreshIdentityFilter(users), SecurityContextHolderFilter.class);
        return http.build();
    }

    private static void problem(HttpServletResponse response, int status, String detail) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
