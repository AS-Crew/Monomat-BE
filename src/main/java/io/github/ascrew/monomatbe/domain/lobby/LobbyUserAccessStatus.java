package io.github.ascrew.monomatbe.domain.lobby;

/**
 * 로비 사용자 접근 상태
 *
 * [사용 목적]
 * 로비 존재 여부, 강퇴 여부, 참여 여부를 개별 Redis 조회로 나누지 않고
 * Repository 경계에서 한 번에 판별하기 위한 상태값
 */
public enum LobbyUserAccessStatus {
    LOBBY_NOT_FOUND,
    KICKED,
    PARTICIPANT,
    NOT_PARTICIPANT
}