package com.spotpulse.service;

import com.spotpulse.dto.UserCreateDto;
import com.spotpulse.dto.UserDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.model.User;
import com.spotpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import com.spotpulse.cache.ReadDataChanged;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Page;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final ApplicationEventPublisher events;


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
    public UserDto create(@Valid UserCreateDto dto) {
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
        User user = EntityMapper.toEntity(dto);
        User saved = saveWithUniqueEmail(user);
        return EntityMapper.toDto(saved);
    }

    @Transactional
    public UserDto update(Long id, @Valid UserCreateDto dto) {
        User user = findOrThrow(id);
        if (userRepository.existsByEmailAndIdNot(dto.getEmail(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
        EntityMapper.updateEntity(user, dto);
        return EntityMapper.toDto(saveWithUniqueEmail(user));
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.deleteById(id);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
    }

    private User saveWithUniqueEmail(User user) {
        try {
            // Flush here so a race rejected by the database is translated before commit.
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
                }
            }
            throw ex;
        }
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
