package com.spotpulse.service;

import com.spotpulse.dto.UserCreateDto;
import com.spotpulse.dto.UserDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.model.User;
import com.spotpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public Page<UserDto> getAll(Pageable pageable) {
        return userRepository.findAll(pageable)
                .map(EntityMapper::toDto);
    }

    public UserDto getById(Long id) {
        User user = findOrThrow(id);
        return EntityMapper.toDto(user);
    }

    @Transactional
    public UserDto create(UserCreateDto dto) {
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
        User user = EntityMapper.toEntity(dto);
        User saved = userRepository.save(user);
        return EntityMapper.toDto(saved);
    }

    @Transactional
    public UserDto update(Long id, UserCreateDto dto) {
        User user = findOrThrow(id);
        EntityMapper.updateEntity(user, dto);
        return EntityMapper.toDto(userRepository.save(user));
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.deleteById(id);
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
