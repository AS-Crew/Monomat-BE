package io.github.ascrew.monomatbe.global.websocket.event;

/**
 * 플레이어가 WebSocket 연결을 해제했을 때 발행되는 이벤트.
 *
 * [위치 선정 이유 — global/websocket/event]
 * 이 이벤트는 WebSocket 연결 해제라는 인프라 이벤트입니다.
 * domain/lobby의 LobbyLeaveEventHandler가 이 이벤트를 수신하여
 * 로비 퇴장 도메인 처리를 수행합니다.
 *
 * 이벤트 객체가 domain/lobby에 위치하면 global.websocket 계층이
 * domain.lobby에 직접 의존하게 되어 의존 방향이 역전됩니다.
 * 따라서 global에 위치시켜 domain -> global 단방향 의존 방향을 유지합니다.
 *
 * @param lobbyCode      퇴장이 발생한 로비 코드
 * @param userIdentifier 퇴장한 사용자 식별자
 */
public record PlayerLeaveEvent(
        String lobbyCode,
        String userIdentifier
) {}