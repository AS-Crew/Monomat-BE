package io.github.ascrew.monomatbe.domain.map.dto;

import io.github.ascrew.monomatbe.domain.map.MapItemPolicy;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ManageMapRequest(
        @NotBlank(message = "맵 제목은 비어 있을 수 없습니다.")
        @Size(max = 50, message = "맵 제목은 50자를 초과할 수 없습니다.")
        String title,

        @Size(max = 255, message = "맵 설명은 255자를 초과할 수 없습니다.")
        String description,

        @NotNull(message = "카테고리는 비어 있을 수 없습니다.")
        MapCategory category,

        boolean isPublic,

        /**
         * 저장 후 활성 상태로 남아야 하는 전체 문제 목록
         * 변경된 문제만 보내는 필드가 아니다.
         *
         * <p>기존 활성 문제는 이 목록에 포함되거나 {@code deletedItemIds}에 포함되어야 한다.
         * 신규 문제는 {@code id = null}로 전달한다.</p>
         */
        @NotNull(message = "문제 목록은 필수입니다.")
        @Size(max = MapItemPolicy.MAX_ITEMS_PER_MAP, message = "한 맵에 등록할 수 있는 문제 수를 초과했습니다.")
        List<@Valid ManageMapItemRequest> items,

        /**
         * 기존 활성 문제 중 삭제할 문제 ID 목록입니다.
         * {@code items[].id}와 중복될 수 없습니다.
         */
        List<@Positive(message = "삭제할 문제 ID는 양수여야 합니다.") Long> deletedItemIds
) {
}