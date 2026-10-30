package com.spotpulse.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.spotpulse.dto.*;
import org.springframework.cache.caffeine.CaffeineCache;

import java.util.List;

/** Store and return detached copies. Neither entities nor callers' mutable DTOs are shared. */
public class SnapshotCaffeineCache extends CaffeineCache {
    public SnapshotCaffeineCache(String name, Cache<Object, Object> cache) {
        super(name, cache, false);
    }

    @Override
    protected Object toStoreValue(Object value) { return super.toStoreValue(copy(value)); }

    @Override
    protected Object fromStoreValue(Object value) {
        Object stored = super.fromStoreValue(value);
        return stored == null ? null : copy(stored);
    }

    private static Object copy(Object value) {
        return switch (value) {
            case SpaceDto space -> {
                var dto = new SpaceDto(space.getId(), space.getName(), space.getDescription(),
                        space.getAddress(), space.getCity(), space.getImageUrl(),
                        space.getRooms().stream().map(SnapshotCaffeineCache::roomCopy).toList(),
                        space.getContactEmail(), space.getLatitude(), space.getLongitude());
                dto.setPremium(space.isPremium()); dto.setFeatured(space.isFeatured()); dto.setPremiumBadge(space.isPremiumBadge());
                yield dto;
            }
            case com.spotpulse.premium.RoomPerformanceDto performance -> performance;
            case RoomDto room -> roomCopy(room);
            case SpaceMapResponse map -> new SpaceMapResponse(List.copyOf(map.items()), map.total(),
                    map.located(), map.missingCoordinates(), map.limit(), map.truncated());
            case BookingAnalyticsDto analytics -> new BookingAnalyticsDto(analytics.from(), analytics.to(),
                    analytics.grouping(), analytics.total(), analytics.statuses(), List.copyOf(analytics.daily()),
                    analytics.popularRoomsLimit(), List.copyOf(analytics.popularRooms()));
            case List<?> suggestions -> {
                if (!suggestions.stream().allMatch(SpaceSuggestionDto.class::isInstance)) {
                    throw new IllegalArgumentException("Only public autocomplete DTO lists may be cached");
                }
                yield List.copyOf(suggestions);
            }
            default -> throw new IllegalArgumentException("Unsupported public read cache value");
        };
    }

    private static RoomDto roomCopy(RoomDto room) {
        return new RoomDto(room.getId(), room.getName(), room.getCapacity(), room.getHourlyRate(),
                room.getSpaceId(), room.getSpaceName());
    }
}
