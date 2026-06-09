package io.github.ascrew.monomatbe.domain.lobby.dto;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 로비 설정 수정 요청 DTO
 *
 * [정책]
 * - 대기실에서 방장만 수정할 수 있다.
 * - maxPlayers, questionCount, timeLimitSeconds는 모두 명시적으로 전달해야 한다.
 * - null 허용 시 "부분 수정" 의미가 섞여 FE 저장 UI와 서버 상태가 어긋날 수 있으므로
 *   이번 API는 전체 설정 저장 방식으로 고정한다.
 *
 * [검증 규칙]
 * - maxPlayers       : 2~8명
 * - questionCount    : 1~50개
 * - timeLimitSeconds : 10~120초
 *
 * [동적 검증]
 * - maxPlayers가 현재 참가자 수보다 작은지 여부
 * - questionCount가 선택된 맵의 등록 곡 수를 초과하는지 여부
 *
 * 위 두 검증은 Redis/DB 상태를 함께 봐야 하므로 Service 계층에서 처리한다.
 */
public record UpdateLobbySettingsRequest(

        @NotNull(message = "최대 인원은 필수입니다.")
        @Min(
                value = LobbyDefaults.MIN_PLAYERS,
                message = "최대 인원은 {value}명 이상이어야 합니다."
        )
        @Max(
                value = LobbyDefaults.MAX_PLAYERS,
                message = "최대 인원은 {value}명 이하이어야 합니다."
        )
        Integer maxPlayers,

        @NotNull(message = "문제 갯수는 필수입니다.")
        @Min(
                value = LobbyDefaults.MIN_QUESTION_COUNT,
                message = "문제 갯수는 {value} 이상이어야 합니다."
        )
        @Max(
                value = LobbyDefaults.MAX_QUESTION_COUNT,
                message = "문제 갯수는 {value} 이하이어야 합니다."
        )
        Integer questionCount,

        @NotNull(message = "제한 시간은 필수입니다.")
        @Min(
                value = LobbyDefaults.MIN_TIME_LIMIT_SECONDS,
                message = "제한 시간은 {value}초 이상이어야 합니다."
        )
        @Max(
                value = LobbyDefaults.MAX_TIME_LIMIT_SECONDS,
                message = "제한 시간은 {value}초 이하이어야 합니다."
        )
        Integer timeLimitSeconds
) {
}