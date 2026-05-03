package io.github.ascrew.monomatbe.domain.lobby.dto;

import lombok.Builder;

/**
 * 로비 생성 응답 DTO.
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
 */
@Builder
public record CreateLobbyResponse(
        Long lobbyId,
        String inviteCode,
        String title,
        int maxPlayers,
        boolean isPrivate,
        String status
) {}