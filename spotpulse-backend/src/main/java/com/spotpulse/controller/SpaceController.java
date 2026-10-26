package com.spotpulse.controller;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.dto.SpaceSuggestionDto;
import com.spotpulse.service.SpaceService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import com.spotpulse.pagination.PaginationSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;

    @GetMapping("/map")
    public com.spotpulse.dto.SpaceMapResponse mapData(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "200") int limit) {
        return spaceService.mapData(city, search, limit);
    }

    @GetMapping("/autocomplete")
    public List<SpaceSuggestionDto> autocomplete(@RequestParam(required = false) String q) {
        return spaceService.autocomplete(q);
    }

    /** GET /api/spaces?page=0&size=10&sort=name,asc  або  ?city=Kyiv  або  ?search=cowork */
    @GetMapping
    public Object getAll(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String search,
            Pageable pageable, HttpServletRequest request) {
        pageable = PaginationSupport.pageable(request, pageable);
        if (search != null && !search.isBlank()) {
            return PaginationSupport.response(spaceService.search(search, pageable), request);
        }
        if (city != null && !city.isBlank()) {
            return PaginationSupport.response(spaceService.getByCity(city, pageable), request);
        }
        return PaginationSupport.response(spaceService.getAll(pageable), request);
    }

    /** GET /api/spaces/{id} */
    @GetMapping("/{id}")
    public SpaceDto getById(@PathVariable Long id) {
        return spaceService.getById(id);
    }

    /** POST /api/spaces */
    @PostMapping
    public ResponseEntity<SpaceDto> create(@Valid @RequestBody SpaceCreateDto dto) {
        SpaceDto created = spaceService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** PUT /api/spaces/{id} */
    @PutMapping("/{id}")
    public SpaceDto update(@PathVariable Long id, @Valid @RequestBody SpaceCreateDto dto) {
        return spaceService.update(id, dto);
    }

    /** DELETE /api/spaces/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        spaceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
