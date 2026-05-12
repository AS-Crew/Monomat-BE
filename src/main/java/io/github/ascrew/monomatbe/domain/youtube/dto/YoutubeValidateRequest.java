package io.github.ascrew.monomatbe.domain.youtube.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YoutubeValidateRequest(
        @NotBlank(message = "YouTube URL은 비어 있을 수 없습니다.")
        @Size(max = 500, message = "YouTube URL은 500자를 초과할 수 없습니다.")
        String youtubeUrl
) {
}
