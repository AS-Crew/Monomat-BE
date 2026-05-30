package io.github.ascrew.monomatbe.domain.map.dto;

import io.github.ascrew.monomatbe.domain.map.MapItemPolicy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReorderMapItemsRequest(
        @Size(max = MapItemPolicy.MAX_ITEMS_PER_MAP,
                message = "한 번에 재정렬할 수 있는 문제는 최대 " + MapItemPolicy.MAX_ITEMS_PER_MAP + "개입니다.")
        @NotEmpty(message = "문제 ID 목록은 비어 있을 수 없습니다.")
        List<@NotNull Long> itemIds
) {
}
