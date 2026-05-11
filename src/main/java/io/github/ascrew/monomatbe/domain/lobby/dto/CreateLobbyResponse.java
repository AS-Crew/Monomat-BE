package io.github.ascrew.monomatbe.domain.lobby.dto;

import lombok.Builder;

/**
 * 로비 생성 응답 DTO.
 *
 * [맵 정보]
 * mapId, mapTitle, mapCategory는 로비 생성 시 맵을 선택하지 않은 경우 null이다.
 *
 * [딥링크 설계]
 * 프론트엔드가 /lobby/{inviteCode} 경로로 바로 이동할 수 있도록 inviteCode를 응답에 포함한다.
 *
 * [필드 구성]
 * - lobbyId    : DB GAME_LOBBY.id (신고 등 참조용)
 * - inviteCode : 6자리 고유 코드 (딥링크 및 초대용)
 * - title      : 로비 제목
 * - maxPlayers : 최대 참여 인원
 * - isPrivate  : 공개/비공개 여부
 * - status     : 로비 상태 (생성 직후 WAITING)
 * - mapId      : 선택된 맵 ID (맵 미선택 시 null)
 * - mapTitle   : 선택된 맵 제목 (맵 미선택 시 null)
 * - mapCategory: 선택된 맵 카테고리 (맵 미선택 시 null)
 */
@Builder
public record CreateLobbyResponse(
        Long lobbyId,
        String inviteCode,
        String title,
        int maxPlayers,
        boolean isPrivate,
        String status,
        Long mapId,
        String mapTitle,
        String mapCategory
) {
}