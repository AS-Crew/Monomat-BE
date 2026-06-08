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
 * - 방장 식별자 및 표시 닉네임
 * - 선택된 맵 정보
 * - 룰 정보 (questionCount, timeLimitSeconds)
 * - 참여자별 nickname / ready / host 상태
 * - 현재 게임 시작 버튼 활성화 가능 여부 (canStart)
 */
@Builder
public record LobbyDetailResponse(
        String inviteCode,
        String title,
        String hostId,
        String hostNickname,
        int maxPlayers,
        int currentPlayers,
        String status,
        Long mapId,
        String mapTitle,
        String mapCategory,
        Integer questionCount,
        Integer timeLimitSeconds,
        List<LobbyPlayerResponse> players,
        boolean canStart
) {
}