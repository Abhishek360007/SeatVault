package com.seatvault.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable, documented pagination envelope.
 *
 * <p>We deliberately do NOT serialize Spring's {@code Page}/{@code PageImpl}
 * directly — its JSON shape is unstable across versions and Spring even logs a
 * warning about it. This record gives the API a contract we control.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
