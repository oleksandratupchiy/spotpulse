package com.spotpulse.service;

import com.spotpulse.dto.RoomCreateDto;
import com.spotpulse.dto.RoomDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.model.Room;
import com.spotpulse.model.Space;
import com.spotpulse.repository.RoomRepository;
import com.spotpulse.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.spotpulse.security.AccessPolicy;
import com.spotpulse.cache.ReadDataChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.cache.annotation.Cacheable;
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
    private final AccessPolicy access;

    private final ApplicationEventPublisher events;


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

    @Cacheable(cacheNames = "roomDetails", key = "@readCaches.key('roomDetails', #id)", condition = "@readCaches.allowed()")
    public RoomDto getById(Long id) {
        Room room = findOrThrow(id);
        return EntityMapper.toDto(room);
    }

    @Transactional
    @PreAuthorize("@access.canManageSpace(#dto.spaceId)")
    public RoomDto create(@Valid RoomCreateDto dto) {
        Space space = spaceRepository.findById(dto.getSpaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        Room room = EntityMapper.toEntity(dto, space);
        Room saved = roomRepository.save(room);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.ROOMS));
        return EntityMapper.toDto(saved);
    }

    @Transactional
    @PreAuthorize("@access.canManageRoom(#id) and @access.canManageSpace(#dto.spaceId)")
    public RoomDto update(Long id, @Valid RoomCreateDto dto) {
        Room room = findOrThrow(id);
        Space space = spaceRepository.findById(dto.getSpaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        EntityMapper.updateEntity(room, dto, space);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.ROOMS));
        return EntityMapper.toDto(roomRepository.save(room));
    }

    @Transactional
    @PreAuthorize("@access.canManageRoom(#id)")
    public void delete(Long id) {
        if (!roomRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        }
        roomRepository.deleteById(id);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.ROOMS));
    }

    private Room findOrThrow(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }
}
