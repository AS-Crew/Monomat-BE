package io.github.ascrew.monomatbe.domain.lobby.dto;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import jakarta.validation.constraints.*;

/**
 * 로비 생성 요청 DTO.
 *
 * [맵 선택 정책]
 * mapId는 선택 사항이다.
 * 로비는 맵 없이 먼저 생성될 수 있으며, 게임 시작 시점에 맵 선택 여부를 검증한다.
 *
 * [기본값 처리]
 * roundCount, timeLimitSeconds는 클라이언트가 null로 생략하면 LobbyDefaults 상수값으로 자동 적용된다.
 *
 * [검증 규칙 — 기능명세서 기준]
 * - title      : 필수, 최대 255자
 * - maxPlayers : 2~8명
 * - mapId      : 선택 사항 (맵 없는 로비 허용), 전달 시 양수
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

        /**
         * 로비에 연결할 맵 ID
         *
         * null이면 맵 미선택 로비로 생성한다.
         * 실제 맵 존재 여부, 삭제 여부, 접근 권한은 LobbyMapPolicy에서 검증한다.
         */
        @Positive(message = "맵 ID는 양수여야 합니다.")
        Long mapId,

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