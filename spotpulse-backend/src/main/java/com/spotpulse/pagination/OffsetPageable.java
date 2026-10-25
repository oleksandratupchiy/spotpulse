package com.spotpulse.pagination;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Keeps the exact offset when Spring Data applies SQL offset/limit. */
public record OffsetPageable(long offset, int pageSize, Sort sort) implements Pageable {
    public OffsetPageable {
        if (offset < 0 || pageSize <= 0 || sort == null) {
            throw new IllegalArgumentException("Invalid offset pagination");
        }
    }

    @Override public long getOffset() { return offset; }
    @Override public int getPageSize() { return pageSize; }
    @Override public Sort getSort() { return sort; }
    @Override public int getPageNumber() { return Math.toIntExact(offset / pageSize); }
    @Override public Pageable next() { return new OffsetPageable(offset + pageSize, pageSize, sort); }
    @Override public Pageable previousOrFirst() {
        return new OffsetPageable(Math.max(0, offset - pageSize), pageSize, sort);
    }
    @Override public Pageable first() { return new OffsetPageable(0, pageSize, sort); }
    @Override public Pageable withPage(int pageNumber) {
        if (pageNumber < 0) throw new IllegalArgumentException("Page must not be negative");
        return new OffsetPageable((long) pageNumber * pageSize, pageSize, sort);
    }
    @Override public boolean hasPrevious() { return offset > 0; }
}
