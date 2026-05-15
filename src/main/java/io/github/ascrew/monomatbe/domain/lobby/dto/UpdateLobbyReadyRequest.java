package io.github.ascrew.monomatbe.domain.lobby.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 로비 참여자의 준비 상태 변경 요청 DTO
 *
 * [정책]
 * - ready=true  : 준비 완료
 * - ready=false : 준비 해제
 *
 * Boolean 타입을 사용하여 클라이언트가 필드를 누락한 경우와 false를 명시한 경우를 구분한다.
 */
public record UpdateLobbyReadyRequest(

        @NotNull(message = "준비 상태는 필수입니다.")
        Boolean ready

) {
}