package com.spotpulse.security;

import com.spotpulse.model.User;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GoogleIdentityService {
    private final UserRepository users;

    @Transactional
    public AppOidcUser resolve(OidcUser identity) {
        String subject = identity.getSubject();
        String email = identity.getEmail();
        if (subject == null || subject.isBlank() || subject.length() > 255
                || !Boolean.TRUE.equals(identity.getEmailVerified()) || email == null
                || email.length() > 255 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw failure("invalid_identity");
        }
        var existing = users.findByOauthProviderAndOauthSubject("google", subject);
        if (existing.isPresent()) return new AppOidcUser(existing.get(), identity);
        email = email.toLowerCase(Locale.ROOT);
        // An email match is not proof that this Google identity owns a legacy account.
        if (users.findByEmailIgnoreCase(email).isPresent()) throw failure("account_migration_required");
        User user = new User();
        String name = identity.getFullName();
        user.setName(name == null || name.isBlank() ? "SpotPulse member" : name.substring(0, Math.min(name.length(), 255)));
        user.setEmail(email); user.setRole(Role.USER);
        user.setOauthProvider("google"); user.setOauthSubject(subject);
        // Unique provider/subject and email constraints reject concurrent conflicting registrations.
        return new AppOidcUser(users.saveAndFlush(user), identity);
    }

    private OAuth2AuthenticationException failure(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
