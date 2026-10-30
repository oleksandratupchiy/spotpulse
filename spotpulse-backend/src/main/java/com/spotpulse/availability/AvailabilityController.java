package com.spotpulse.availability;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController @RequiredArgsConstructor
public class AvailabilityController {
    private final AvailabilityService availability;

    @GetMapping("/api/rooms/{id}/availability")
    public ResponseEntity<AvailabilityService.Snapshot> read(@PathVariable Long id,
            @RequestParam LocalDateTime from, @RequestParam LocalDateTime to) {
        // Deliberately bypass B9 caches: notifications must never lead to a stale cached snapshot.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(availability.read(id, from, to));
    }
}
