package io.github.ascrew.monomatbe.domain.lobby.dto;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 로비 생성 요청 DTO
 *
 * [맵 선택 정책]
 * mapId는 선택 사항이다.
 * 로비는 맵 없이 먼저 생성될 수 있으며, 게임 시작 시점에 맵 선택 여부를 검증한다.
 *
 * [기본값 처리]
 * 클라이언트가 maxPlayers, questionCount, timeLimitSeconds를 생략하면
 * compact constructor에서 LobbyDefaults 기준 기본값을 적용한다.
 *
 * [검증 규칙]
 * - title            : 필수, 최대 255자
 * - maxPlayers       : 2~8명, 생략 시 4명
 * - mapId            : 선택 사항, 전달 시 양수
 * - questionCount    : 1~50개, 생략 시 10개
 * - timeLimitSeconds : 10~120초, 생략 시 30초
 */
public record CreateLobbyRequest(

        @NotBlank(message = "로비 제목은 비어 있을 수 없습니다.")
        @Size(max = 255, message = "로비 제목은 255자를 초과할 수 없습니다.")
        String title,

        @Min(
                value = LobbyDefaults.MIN_PLAYERS,
                message = "최대 인원은 2명 이상이어야 합니다."
        )
        @Max(
                value = LobbyDefaults.MAX_PLAYERS,
                message = "최대 인원은 8명 이하이어야 합니다."
        )
        Integer maxPlayers,

        boolean isPrivate,

        /**
         * 로비에 연결할 맵 ID
         *
         * null이면 맵 미선택 로비로 생성한다.
         * 실제 맵 존재 여부, 삭제 여부, 접근 권한은 LobbyMapPolicy에서 검증한다.
         */
        @Positive(message = "맵 ID는 양수여야 합니다.")
        Long mapId,

        /**
         * 문제 갯수 또는 라운드 수
         *
         * null이면 LobbyDefaults.DEFAULT_QUESTION_COUNT를 적용한다.
         * 선택된 맵의 등록 곡 수보다 큰 경우 LobbyCreateService에서 400으로 거부한다.
         */
        @Min(
                value = LobbyDefaults.MIN_QUESTION_COUNT,
                message = "문제 갯수는 1 이상이어야 합니다."
        )
        @Max(
                value = LobbyDefaults.MAX_QUESTION_COUNT,
                message = "문제 갯수는 50 이하이어야 합니다."
        )
        Integer questionCount,

        @Min(
                value = LobbyDefaults.MIN_TIME_LIMIT_SECONDS,
                message = "제한 시간은 10초 이상이어야 합니다."
        )
        @Max(
                value = LobbyDefaults.MAX_TIME_LIMIT_SECONDS,
                message = "제한 시간은 120초 이하이어야 합니다."
        )
        Integer timeLimitSeconds
) {
    public CreateLobbyRequest {
        if (maxPlayers == null) {
            maxPlayers = LobbyDefaults.DEFAULT_MAX_PLAYERS;
        }

        if (questionCount == null) {
            questionCount = LobbyDefaults.DEFAULT_QUESTION_COUNT;
        }

        if (timeLimitSeconds == null) {
            timeLimitSeconds = LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS;
        }
    }
}