package io.github.ascrew.monomatbe.domain.lobby.dto;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로비 생성 요청 DTO.
 *
 * [기본값 처리]
 * roundCount, timeLimitSeconds는 클라이언트가 null로 생략하면 LobbyDefaults 상수값으로 자동 적용된다.
 *
 * [검증 규칙 — 기능명세서 기준]
 * - title      : 필수, 최대 255자
 * - maxPlayers : 2~8명
 * - roundCount : 1~20 (생략 시 기본값 5)
 * - timeLimitSeconds : 10~120초 (생략 시 기본값 30)
 */
public record CreateLobbyRequest(

        @NotBlank(message = "로비 제목은 비어 있을 수 없습니다.")
        @Size(max = 255, message = "로비 제목은 255자를 초과할 수 없습니다.")
        String title,

        @Min(value = 2, message = "최대 인원은 2명 이상이어야 합니다.")
        @Max(value = 8, message = "최대 인원은 8명 이하이어야 합니다.")
        int maxPlayers,

        boolean isPrivate,

        @Min(value = 1, message = "라운드 수는 1 이상이어야 합니다.")
        @Max(value = 20, message = "라운드 수는 20 이하이어야 합니다.")
        Integer roundCount,

        @Min(value = 10, message = "제한 시간은 10초 이상이어야 합니다.")
        @Max(value = 120, message = "제한 시간은 120초 이하이어야 합니다.")
        Integer timeLimitSeconds
) {
    /**
     * 기본값 적용 compact constructor.
     * roundCount, timeLimitSeconds가 null이면 LobbyDefaults 상수값으로 대체한다.
     * Integer(nullable) 선언으로 @Min 검증과 충돌 없이 기본값을 적용한다.
     */
    public CreateLobbyRequest {
        if (roundCount == null) {
            roundCount = LobbyDefaults.DEFAULT_ROUND_COUNT;
        }
        if (timeLimitSeconds == null) {
            timeLimitSeconds = LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS;
        }
    }
}