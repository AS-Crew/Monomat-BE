package io.github.ascrew.monomatbe.domain.map.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReorderMapItemsRequest(
        @NotEmpty(message = "문제 ID 목록은 비어 있을 수 없습니다.")
        List<@NotNull Long> itemIds
) {
}
