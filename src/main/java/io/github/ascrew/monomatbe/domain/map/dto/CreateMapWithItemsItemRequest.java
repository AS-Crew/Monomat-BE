package io.github.ascrew.monomatbe.domain.map.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateMapWithItemsItemRequest(
        @NotNull(message = "문제 순서는 필수입니다.")
        @Min(value = 1, message = "문제 순서는 1 이상이어야 합니다.")
        Integer orderNum,

        @NotBlank(message = "YouTube URL은 비어 있을 수 없습니다.")
        @Size(max = 500, message = "YouTube URL은 500자를 초과할 수 없습니다.")
        String youtubeUrl,

        @NotNull(message = "시작 시간은 필수입니다.")
        @Min(value = 0, message = "시작 시간은 0 이상이어야 합니다.")
        Integer startTime,

        @NotNull(message = "정답 목록은 필수입니다.")
        @Size(min = 1, max = 5, message = "정답은 1개 이상 5개 이하로 입력해야 합니다.")
        List<@NotBlank(message = "정답 값은 비어 있을 수 없습니다.")
        @Size(max = 255, message = "정답 값은 255자를 초과할 수 없습니다.")
                String> answers,

        @NotBlank(message = "힌트는 필수입니다.")
        @Size(max = 50, message = "힌트는 50자를 초과할 수 없습니다.")
        String hint,

        @Min(value = 1, message = "힌트 공개 시간은 1초 이상이어야 합니다.")
        @Max(value = 100, message = "힌트 공개 시간은 100초 이하여야 합니다.")
        Integer hintTime
) {
}