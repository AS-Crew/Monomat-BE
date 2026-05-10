package io.github.ascrew.monomatbe.domain.lobby;

/**
 * 로비 강퇴 Lua 스크립트 실행 결과
 *
 * [설계 의도]
 * kick_lobby.lua의 문자열 반환값을 서비스 레이어까지 직접 노출하지 않고,
 * 도메인 의미를 가진 타입으로 반환하여 처리한다.
 */
public sealed interface KickLobbyResult permits
        KickLobbyResult.Kicked,
        KickLobbyResult.LobbyNotFound,
        KickLobbyResult.HostNotFound,
        KickLobbyResult.Forbidden,
        KickLobbyResult.CannotKickSelf,
        KickLobbyResult.TargetNotParticipant,
        KickLobbyResult.Error {

    /**
     * 강퇴 성공.
     *
     * @param lobbyCode             로비 초대 코드
     * @param targetUserIdentifier  강퇴 대상 사용자 식별자
     * @param targetWsSessionId     강퇴 대상의 현재 유효 WebSocket 세션 ID. 없을 수 있음.
     */
    record Kicked(
            String lobbyCode,
            String targetUserIdentifier,
            String targetWsSessionId
    ) implements KickLobbyResult {
    }

    /** 로비가 Redis에 존재하지 않음. */
    record LobbyNotFound(String lobbyCode) implements KickLobbyResult {
    }

    /** 로비의 방장 정보가 유효하지 않음. */
    record HostNotFound(String lobbyCode) implements KickLobbyResult {
    }

    /** 요청자가 방장이 아님. */
    record Forbidden(String lobbyCode, String requesterIdentifier) implements KickLobbyResult {
    }

    /** 방장이 자기 자신을 강퇴하려고 함. */
    record CannotKickSelf(String lobbyCode, String requesterIdentifier) implements KickLobbyResult {
    }

    /** 강퇴 대상이 현재 로비 참여자가 아님. */
    record TargetNotParticipant(
            String lobbyCode,
            String targetUserIdentifier
    ) implements KickLobbyResult {
    }

    /** Lua 실행 실패 또는 알 수 없는 반환값. */
    record Error(String reason) implements KickLobbyResult {
    }
}