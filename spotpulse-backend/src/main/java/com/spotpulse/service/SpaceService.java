package com.spotpulse.service;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.model.Space;
import com.spotpulse.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpaceService {

    public static final int SITEMAP_PAGE_SIZE = 1000;

    private final SpaceRepository spaceRepository;

    public long countSpaces() {
        return spaceRepository.count();
    }

    public List<Long> getSitemapIds(int page) {
        return spaceRepository.findSitemapIds(PageRequest.of(page, SITEMAP_PAGE_SIZE));
    }

    public Page<SpaceDto> getAll(Pageable pageable) {
        return spaceRepository.findAll(pageable)
                .map(EntityMapper::toDto);
    }

    public Page<SpaceDto> getByCity(String city, Pageable pageable) {
        return spaceRepository.findByCity(city, pageable)
                .map(EntityMapper::toDto);
    }

    public Page<SpaceDto> search(String query, Pageable pageable) {
        return spaceRepository.search(query, pageable)
                .map(EntityMapper::toDto);
    }

    public SpaceDto getById(Long id) {
        Space space = findOrThrow(id);
        return EntityMapper.toDto(space);
    }

    @Transactional
    public SpaceDto create(@Valid SpaceCreateDto dto) {
        Space space = EntityMapper.toEntity(dto);
        Space saved = spaceRepository.save(space);
        return EntityMapper.toDto(saved);
    }

    @Transactional
    public SpaceDto update(Long id, @Valid SpaceCreateDto dto) {
        Space space = findOrThrow(id);
        EntityMapper.updateEntity(space, dto);
        return EntityMapper.toDto(spaceRepository.save(space));
    }

    @Transactional
    public void delete(Long id) {
        if (!spaceRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found");
        }
        spaceRepository.deleteById(id);
    }

    private Space findOrThrow(Long id) {
        return spaceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
    }
}
