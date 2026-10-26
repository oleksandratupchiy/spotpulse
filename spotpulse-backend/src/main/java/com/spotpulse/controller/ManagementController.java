package com.spotpulse.controller;

import com.spotpulse.service.*;
import com.spotpulse.pagination.PaginationSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ManagementController {
    private final SpaceService spaces;
    private final BookingService bookings;

    @GetMapping("/api/management/spaces/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("@access.canManageSpace(#id)")
    public Object space(@PathVariable Long id) { return spaces.getById(id); }

    @GetMapping("/api/management/spaces")
    public Object spaces(Pageable pageable, HttpServletRequest request) {
        return PaginationSupport.response(spaces.getManaged(PaginationSupport.pageable(request, pageable)), request);
    }
    @GetMapping("/api/management/bookings")
    public Object bookings(Pageable pageable, HttpServletRequest request) {
        return PaginationSupport.response(bookings.managed(PaginationSupport.pageable(request, pageable)), request);
    }
    public record OwnerAssignment(@NotNull @Positive Long ownerId) { }
    @PutMapping("/api/admin/spaces/{id}/owner")
    public Object assign(@PathVariable Long id, @Valid @RequestBody OwnerAssignment input) {
        return spaces.assignOwner(id, input.ownerId());
    }
}
