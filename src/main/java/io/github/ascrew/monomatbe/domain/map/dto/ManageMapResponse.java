package io.github.ascrew.monomatbe.domain.map.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ManageMapResponse(
        MapDetailResponse map,
        List<MapItemResponse> items
) {
}