package com.spotpulse.security;

import com.spotpulse.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Roles are refreshed from the database on each HTTP request, never from Google claims or cache. */
public class FreshIdentityFilter extends OncePerRequestFilter {
    private final UserRepository users;
    public FreshIdentityFilter(UserRepository users) { this.users = users; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppOidcUser principal) {
            var user = users.findById(principal.getUserId());
            if (user.isEmpty() || !"google".equals(user.get().getOauthProvider())
                    || !principal.getSubject().equals(user.get().getOauthSubject())) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
            } else {
                var refreshed = new AppOidcUser(user.get(), principal);
                if (!auth.getAuthorities().equals(refreshed.getAuthorities())) {
                    var replacement = new OAuth2AuthenticationToken(refreshed, refreshed.getAuthorities(), "google");
                    SecurityContextHolder.getContext().setAuthentication(replacement);
                    new HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.getContext(), request, response);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
