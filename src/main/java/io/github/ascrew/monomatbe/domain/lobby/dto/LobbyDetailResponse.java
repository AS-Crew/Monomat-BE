package io.github.ascrew.monomatbe.domain.lobby.dto;

import lombok.Builder;

import java.util.List;

/**
 * 로비 대기실 상세 응답 DTO
 *
 * 사용 목적 : 로비 대기실 화면에서 필요한 서버 상태를 한 번에 내려준다.
 *
 * [포함 정보]
 * - 로비 기본 정보
 * - 선택된 맵 정보
 * - 룰 정보 (roundCount, timeLimitSeconds)
 * - 참여자별 ready 상태
 * - 현재 게임 시작 버튼 활성화 가능 여부 (canStart)
 */
@Builder
public record LobbyDetailResponse(
        String inviteCode,
        String title,
        String hostId,
        int maxPlayers,
        int currentPlayers,
        String status,
        Long mapId,
        String mapTitle,
        String mapCategory,
        Integer roundCount,
        Integer timeLimitSeconds,
        List<LobbyPlayerResponse> players,
        boolean canStart
) {
}