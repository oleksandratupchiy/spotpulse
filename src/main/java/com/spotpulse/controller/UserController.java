package com.spotpulse.controller;

import com.spotpulse.dto.UserCreateDto;
import com.spotpulse.dto.UserDto;
import com.spotpulse.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** GET /api/users?page=0&size=10 */
    @GetMapping
    public Page<UserDto> getAll(Pageable pageable) {
        return userService.getAll(pageable);
    }

    /** GET /api/users/{id} */
    @GetMapping("/{id}")
    public UserDto getById(@PathVariable Long id) {
        return userService.getById(id);
    }

    /** POST /api/users */
    @PostMapping
    public ResponseEntity<UserDto> create(@RequestBody UserCreateDto dto) {
        UserDto created = userService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** PUT /api/users/{id} */
    @PutMapping("/{id}")
    public UserDto update(@PathVariable Long id, @RequestBody UserCreateDto dto) {
        return userService.update(id, dto);
    }

    /** DELETE /api/users/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
