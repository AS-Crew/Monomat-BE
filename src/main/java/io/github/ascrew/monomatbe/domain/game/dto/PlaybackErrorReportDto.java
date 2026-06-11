package io.github.ascrew.monomatbe.domain.game.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 클라이언트 재생 오류 보고 DTO.
 */
public record PlaybackErrorReportDto(
        @NotNull(message = "라운드 번호는 필수입니다.")
        Integer roundNo,

        @Size(max = 100, message = "오류 코드는 최대 100자까지 전송 가능합니다.")
        String errorCode,

        @Size(max = 500, message = "오류 메시지는 최대 500자까지 전송 가능합니다.")
        String message
) {
}
