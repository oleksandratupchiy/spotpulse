package com.spotpulse.controller;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.service.SpaceService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;

    /** GET /api/spaces?page=0&size=10&sort=name,asc  або  ?city=Kyiv  або  ?search=cowork */
    @GetMapping
    public Page<SpaceDto> getAll(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        if (search != null && !search.isBlank()) {
            return spaceService.search(search, pageable);
        }
        if (city != null && !city.isBlank()) {
            return spaceService.getByCity(city, pageable);
        }
        return spaceService.getAll(pageable);
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
