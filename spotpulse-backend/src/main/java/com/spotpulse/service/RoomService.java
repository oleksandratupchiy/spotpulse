package com.spotpulse.service;

import com.spotpulse.dto.RoomCreateDto;
import com.spotpulse.dto.RoomDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.model.Room;
import com.spotpulse.model.Space;
import com.spotpulse.repository.RoomRepository;
import com.spotpulse.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {

    private final RoomRepository roomRepository;
    private final SpaceRepository spaceRepository;

    public Page<RoomDto> getAll(Pageable pageable) {
        return roomRepository.findAll(pageable)
                .map(EntityMapper::toDto);
    }

    public Page<RoomDto> getBySpaceId(Long spaceId, Pageable pageable) {
        return roomRepository.findBySpaceId(spaceId, pageable)
                .map(EntityMapper::toDto);
    }

    public RoomDto getById(Long id) {
        Room room = findOrThrow(id);
        return EntityMapper.toDto(room);
    }

    @Transactional
    public RoomDto create(@Valid RoomCreateDto dto) {
        Space space = spaceRepository.findById(dto.getSpaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        Room room = EntityMapper.toEntity(dto, space);
        Room saved = roomRepository.save(room);
        return EntityMapper.toDto(saved);
    }

    @Transactional
    public RoomDto update(Long id, @Valid RoomCreateDto dto) {
        Room room = findOrThrow(id);
        Space space = spaceRepository.findById(dto.getSpaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        EntityMapper.updateEntity(room, dto, space);
        return EntityMapper.toDto(roomRepository.save(room));
    }

    @Transactional
    public void delete(Long id) {
        if (!roomRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        }
        roomRepository.deleteById(id);
    }

    private Room findOrThrow(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }
}
