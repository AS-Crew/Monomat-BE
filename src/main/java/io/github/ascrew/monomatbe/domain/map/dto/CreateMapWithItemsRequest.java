package io.github.ascrew.monomatbe.domain.map.dto;

import io.github.ascrew.monomatbe.domain.map.MapItemPolicy;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateMapWithItemsRequest(
        @NotBlank(message = "맵 제목은 비어 있을 수 없습니다.")
        @Size(max = 50, message = "맵 제목은 50자를 초과할 수 없습니다.")
        String title,

        @Size(max = 255, message = "맵 설명은 255자를 초과할 수 없습니다.")
        String description,

        @NotNull(message = "카테고리는 비어 있을 수 없습니다.")
        MapCategory category,

        boolean isPublic,

        @NotNull(message = "문제 목록은 필수입니다.")
        @Size(
                min = 1,
                max = MapItemPolicy.MAX_ITEMS_PER_MAP,
                message = "문제는 1개 이상 등록할 수 있으며, 한 맵에 등록할 수 있는 문제 수를 초과할 수 없습니다."
        )
        List<@Valid CreateMapWithItemsItemRequest> items
) {
}