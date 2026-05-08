package io.github.ascrew.monomatbe.domain.map.dto;

import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMapRequest(
        @NotBlank(message = "맵 제목은 비어 있을 수 없습니다.")
        @Size(max = 50, message = "맵 제목은 50자를 초과할 수 없습니다.")
        String title,

        @Size(max = 255, message = "맵 설명은 255자를 초과할 수 없습니다.")
        String description,

        @NotNull(message = "카테고리는 비어 있을 수 없습니다.")
        MapCategory category,

        boolean isPublic
) {
}
