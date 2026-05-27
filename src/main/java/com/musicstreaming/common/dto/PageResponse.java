package com.musicstreaming.common.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private int pageSize;

    public static <T> PageResponse<T> of(List<T> content, long totalElements, int page, int size) {
        int totalPages = (size > 0) ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PageResponse<>(content, totalElements, totalPages, page, size);
    }
}
