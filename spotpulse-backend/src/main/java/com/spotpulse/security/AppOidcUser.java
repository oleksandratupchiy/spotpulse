package com.spotpulse.security;

import com.spotpulse.model.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.util.List;

/** Session-only principal. Controllers must return an explicit safe DTO, never this object. */
public class AppOidcUser extends DefaultOidcUser {
    private final Long userId;
    public AppOidcUser(User local, OidcUser oidc) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + local.getRole().name())), oidc.getIdToken(), oidc.getUserInfo(), "sub");
        userId = local.getId();
    }
    public Long getUserId() { return userId; }
}
