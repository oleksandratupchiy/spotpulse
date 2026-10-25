package com.spotpulse.dto;

import java.util.List;

public record OffsetPageDto<T>(List<T> items, long skip, int limit, long total, String nextLink) {
}
