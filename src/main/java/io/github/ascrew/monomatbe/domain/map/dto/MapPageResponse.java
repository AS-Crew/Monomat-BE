package io.github.ascrew.monomatbe.domain.map.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record MapPageResponse(
        List<MapSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
