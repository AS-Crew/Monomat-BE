package io.github.ascrew.monomatbe.domain.lobby.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로비 유저 강퇴 요청 DTO
 *
 * [전송 경로]
 * 클라이언트 -> 서버 : /app/lobby/{code}/kick
 *
 * [targetUserIdentifier]
 * 강퇴 대상 사용자의 Redis 기준 사용자 식별자이다.
 * 현재 로비 참여자 Set에는 DB userId가 아니라 userIdentifier (UUID)가 저장되므로,
 * 강퇴 대상도 동일 식별자를 사용한다.
 */
public record KickLobbyPlayerRequest(

        @NotBlank(message = "강퇴 대상 식별자는 비어 있을 수 없습니다.")
        String targetUserIdentifier
){
}