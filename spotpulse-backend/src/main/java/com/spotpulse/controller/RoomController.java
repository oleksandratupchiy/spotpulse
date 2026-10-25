package com.spotpulse.controller;

import com.spotpulse.dto.RoomCreateDto;
import com.spotpulse.dto.RoomDto;
import com.spotpulse.service.RoomService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import com.spotpulse.pagination.PaginationSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /** GET /api/rooms?page=0&size=10  або  GET /api/rooms?spaceId=1 */
    @GetMapping
    public Object getAll(@RequestParam(required = false) Long spaceId, Pageable pageable, HttpServletRequest request) {
        pageable = PaginationSupport.pageable(request, pageable);
        if (spaceId != null) {
            return PaginationSupport.response(roomService.getBySpaceId(spaceId, pageable), request);
        }
        return PaginationSupport.response(roomService.getAll(pageable), request);
    }

    /** GET /api/rooms/{id} */
    @GetMapping("/{id}")
    public RoomDto getById(@PathVariable Long id) {
        return roomService.getById(id);
    }

    /** POST /api/rooms */
    @PostMapping
    public ResponseEntity<RoomDto> create(@Valid @RequestBody RoomCreateDto dto) {
        RoomDto created = roomService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** PUT /api/rooms/{id} */
    @PutMapping("/{id}")
    public RoomDto update(@PathVariable Long id, @Valid @RequestBody RoomCreateDto dto) {
        return roomService.update(id, dto);
    }

    /** DELETE /api/rooms/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        roomService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
