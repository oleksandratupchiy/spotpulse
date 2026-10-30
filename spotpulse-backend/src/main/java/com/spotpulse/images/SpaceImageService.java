package com.spotpulse.images;

import com.spotpulse.cache.ReadDataChanged;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.repository.SpaceRepository;
import com.spotpulse.security.AccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class SpaceImageService {
    private final SpaceRepository spaces;
    private final AccessPolicy access;
    private final ImageValidation validation;
    private final ImageStorage storage;
    private final ApplicationEventPublisher events;

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN') and @access.canManageSpace(#id)")
    public SpaceDto attach(Long id, MultipartFile file) {
        var space = spaces.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found"));
        // Recheck after locking in case ownership changed while waiting.
        if (!access.admin() && (space.getOwner() == null || !space.getOwner().getId().equals(access.userId())))
            throw new AccessDeniedException("Access denied");
        var created = storage.store(validation.validate(file));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.discard(created);
                else storage.committed(created);
            }
        });
        space.setImageUrl(created.reference());
        space.setImageStorageProvider(created.provider());
        space.setImageStorageKey(created.key());
        space.setImageAssetId(created.assetId());
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.SPACES));
        // Previous image objects are deliberately retained: another record/cache may still reference them.
        return EntityMapper.toDto(space);
    }
}
