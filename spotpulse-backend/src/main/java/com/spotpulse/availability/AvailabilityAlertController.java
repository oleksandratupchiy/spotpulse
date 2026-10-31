package com.spotpulse.availability;

import com.spotpulse.pagination.PaginationSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController @RequestMapping("/api/availability-alerts") @RequiredArgsConstructor
public class AvailabilityAlertController {
    private final AvailabilityAlertService alerts;
    @PostMapping public ResponseEntity<AvailabilityAlertService.View> create(@RequestBody AvailabilityAlertService.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(alerts.create(input));
    }
    @GetMapping public ResponseEntity<Object> list(@RequestParam(required = false) Long roomId,
            @RequestParam(required = false) LocalDateTime startTime, @RequestParam(required = false) LocalDateTime endTime,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            Pageable page, HttpServletRequest request) {
        page = PaginationSupport.pageable(request, page);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PaginationSupport.response(alerts.list(roomId, startTime, endTime, activeOnly, page), request));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> cancel(@PathVariable Long id) {
        alerts.cancel(id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
