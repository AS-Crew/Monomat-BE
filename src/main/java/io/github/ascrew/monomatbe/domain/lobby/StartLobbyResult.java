package io.github.ascrew.monomatbe.domain.lobby;

/**
 * 로비 게임 시작 Lua 스크립트 실행 결과
 *
 * [설계 의도]
 * start_lobby.lua의 문자열 반환값을 서비스 레이어에 직접 노출하지 않고,
 * 도메인 의미를 가진 타입으로 변환하여 처리한다.
 */
public sealed interface StartLobbyResult permits
        StartLobbyResult.Started,
        StartLobbyResult.LobbyNotFound,
        StartLobbyResult.HostNotFound,
        StartLobbyResult.Forbidden,
        StartLobbyResult.LobbyNotWaiting,
        StartLobbyResult.MapNotSelected,
        StartLobbyResult.NoPlayer,
        StartLobbyResult.NotReady,
        StartLobbyResult.Error {

    /**
     * 게임 시작 성공.
     *
     * @param lobbyCode 로비 초대 코드
     */
    record Started(String lobbyCode) implements StartLobbyResult {
    }

    /** 로비가 Redis에 존재하지 않음 */
    record LobbyNotFound(String lobbyCode) implements StartLobbyResult {
    }

    /** 로비의 방장 정보가 유효하지 않음 */
    record HostNotFound(String lobbyCode) implements StartLobbyResult {
    }

    /** 요청자가 방장이 아님 */
    record Forbidden(String lobbyCode, String requesterIdentifier) implements StartLobbyResult {
    }

    /** 로비 상태가 WAITING이 아님 */
    record LobbyNotWaiting(String lobbyCode) implements StartLobbyResult {
    }

    /** 선택된 맵이 없음 */
    record MapNotSelected(String lobbyCode) implements StartLobbyResult {
    }

    /** 방장을 제외한 참여자가 없음 */
    record NoPlayer(String lobbyCode) implements StartLobbyResult {
    }

    /**
     * 준비하지 않은 참여자가 있음
     *
     * @param lobbyCode 로비 초대 코드
     * @param userIdentifier 준비하지 않은 참여자 식별자
     */
    record NotReady(String lobbyCode, String userIdentifier) implements StartLobbyResult {
    }

    /** Lua 실행 실패 또는 알 수 없는 반환값 */
    record Error(String reason) implements StartLobbyResult {
    }
}