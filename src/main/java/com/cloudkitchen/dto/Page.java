package com.cloudkitchen.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One page of results out of {@code total}. Pages are numbered from 1. */
public record Page<T>(List<T> items, int page, int pageSize, long total) {

    public Page {
        if (page < 1 || pageSize < 1 || total < 0) {
            throw new IllegalArgumentException("page and pageSize must be at least 1 and total at least 0");
        }
        items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** Number of pages needed for {@code total} items; at least 1 so that an empty list still has a first page. */
    public int totalPages() {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
    }
}
