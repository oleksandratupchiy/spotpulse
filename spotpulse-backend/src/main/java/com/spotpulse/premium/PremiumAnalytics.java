package com.spotpulse.premium;

import com.spotpulse.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service @RequiredArgsConstructor
public class PremiumAnalytics {
    private final FeatureFlags flags;
    private final SpaceRepository spaces;
    private final RoomPerformanceReads reads;

    // This gate is outside cache advice, so a warm cache can never bypass revocation.
    @PreAuthorize("@access.canManageSpace(#id)")
    public RoomPerformanceDto get(Long id, LocalDate from, LocalDate to) {
        var space = spaces.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        if (!space.isPremium()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This space does not have administrator-granted premium access");
        var state = flags.state();
        if (!state.analytics()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Premium analytics is currently disabled by the administrator");
        if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9998
                || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 366)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an inclusive date range of 1 to 366 days within years 0001-9998");
        return reads.read(id, from, to);
    }
}
