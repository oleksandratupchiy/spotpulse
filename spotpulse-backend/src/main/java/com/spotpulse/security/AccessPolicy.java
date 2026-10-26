package com.spotpulse.security;

import com.spotpulse.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("access")
@RequiredArgsConstructor
public class AccessPolicy {
    private final SpaceRepository spaces;
    private final RoomRepository rooms;
    private final BookingRepository bookings;

    public Authentication authentication() { return SecurityContextHolder.getContext().getAuthentication(); }
    public boolean admin() { return role("ADMIN"); }
    public boolean role(String role) {
        var auth = authentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }
    public Long optionalUserId() {
        var auth = authentication();
        return auth != null && auth.getPrincipal() instanceof AppOidcUser user ? user.getUserId() : null;
    }
    public Long userId() {
        Long id = optionalUserId();
        if (id == null) throw new AccessDeniedException("An authenticated SpotPulse identity is required");
        return id;
    }
    public boolean canManageSpace(Long id) {
        return admin() || (role("OWNER") && spaces.existsByIdAndOwnerId(id, userId()));
    }
    public boolean canManageRoom(Long id) {
        return admin() || (role("OWNER") && rooms.existsByIdAndSpaceOwnerId(id, userId()));
    }
    public boolean canReadBooking(Long id) {
        return admin() || (optionalUserId() != null && (bookings.existsByIdAndUserId(id, userId())
                || (role("OWNER") && bookings.existsByIdAndRoomSpaceOwnerId(id, userId()))));
    }
    public Long analyticsOwnerId() {
        if (admin()) return null;
        if (!role("OWNER")) throw new AccessDeniedException("Management analytics requires OWNER or ADMIN");
        return userId();
    }
    public String analyticsScope() { return admin() ? "platform" : "owner:" + analyticsOwnerId(); }
}
