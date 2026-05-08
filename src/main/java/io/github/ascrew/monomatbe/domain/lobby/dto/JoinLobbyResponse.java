package io.github.ascrew.monomatbe.domain.lobby.dto;

import lombok.Builder;

/**
 * 초대 코드 기반 로비 입장 응답 DTO
 *
 * [currentPlayers]
 * REST 응답 시점의 스냅샷이며, WebSocket 연결 후 REFRESH_LOBBY_INFO 신호로
 * 최신 상태를 다시 조회하므로 일시적 불일치는 허용한다.
 *
 * [mapCategory]
 * 맵 선택 이슈에서 반정규화 방식으로 Redis Hash에 저장될 예정이다.
 * 현재는 맵 선택 기능이 구현되지 않았으므로 null로 내려간다.
 */
@Builder
public record JoinLobbyResponse(
        // 로비 초대 코드 (WebSocket 구독 경로에 사용)
        String inviteCode,
        // 로비 제목
        String title,
        // 방장 사용자 식별자
        String hostId,
        // 최대 참여 인원
        int maxPlayers,
        // 현재 참여 인원 (응답 시점 스냅샷)
        int currentPlayers,
        // 로비 상태 (WAITING | PLAYING)
        String status,
        // 선택된 맵의 카테고리 (미선택 시 null)
        String mapCategory
) {
}
