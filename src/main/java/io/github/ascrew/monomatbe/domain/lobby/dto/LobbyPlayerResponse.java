package io.github.ascrew.monomatbe.domain.lobby.dto;

/**
 * 로비 상세 응답에 포함되는 참여자 정보 DTO
 *
 * [정책]
 * - userIdentifier는 Redis와 WebSocket에서 사용하는 사용자 식별자이다.
 * - 방장은 ready 대상에서 제외되므로 ready=false로 내려갈 수 있다.
 * - FE는 host=true 여부를 기준으로 방장 UI와 ready UI를 분리한다.
 */
public record LobbyPlayerResponse(
        String userIdentifier,
        boolean host,
        boolean ready
) {
}