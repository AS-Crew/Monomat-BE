package io.github.ascrew.monomatbe.domain.lobby.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 로비 맵 변경 요청 DTO.
 *
 * mapId 유효성(존재 여부, 삭제 여부, 접근 권한)은 LobbyMapPolicy에서 검증한다.
 */
public record UpdateLobbyMapRequest(

        @NotNull(message = "맵 ID는 필수입니다.")
        @Positive(message = "맵 ID는 양수여야 합니다.")
        Long mapId
) {
}
