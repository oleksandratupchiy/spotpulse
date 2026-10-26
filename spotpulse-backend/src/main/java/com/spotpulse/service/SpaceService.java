package com.spotpulse.service;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.dto.SpaceSuggestionDto;
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
import java.util.Locale;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpaceService {

    public static final int SITEMAP_PAGE_SIZE = 1000;
    public static final int AUTOCOMPLETE_LIMIT = 8;
    public static final int AUTOCOMPLETE_MAX_LENGTH = 100;

    private final SpaceRepository spaceRepository;

    public List<SpaceSuggestionDto> autocomplete(String query) {
        if (query == null) return List.of();
        if (query.length() > AUTOCOMPLETE_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query must be at most 100 characters");
        }
        String trimmed = query.strip();
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query contains invalid characters");
        }
        // Spaces and punctuation do not count toward the three meaningful character threshold.
        if (trimmed.codePoints().filter(Character::isLetterOrDigit).count() < 3) return List.of();
        String pattern = "%" + trimmed.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return spaceRepository.autocomplete(pattern, PageRequest.of(0, AUTOCOMPLETE_LIMIT));
    }

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
