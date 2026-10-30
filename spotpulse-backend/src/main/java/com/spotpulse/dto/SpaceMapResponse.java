package com.spotpulse.dto;

import java.util.List;

public record SpaceMapResponse(List<SpaceMapDto> items, long total, long located,
                               long missingCoordinates, int limit, boolean truncated) { }


