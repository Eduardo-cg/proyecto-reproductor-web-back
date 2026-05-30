package com.musicstreaming.common.util;

import org.springframework.data.domain.Sort;

import java.util.Map;

public final class SortHelper {

    private SortHelper() {
    }

    public static Sort build(String sortBy, String sortDirection, Map<String, String> propertyMapping, String defaultProperty) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String property = propertyMapping.getOrDefault(sortBy != null ? sortBy.toLowerCase() : "", defaultProperty);
        return Sort.by(direction, property);
    }
}
