package io.github.ascrew.monomatbe.global.websocket.event;

/**
 * 인게임(PLAYING 상태)에서 플레이어가 WebSocket 연결을 해제(이탈)했을 때 발행되는 이벤트.
 *
 * @param lobbyCode      이탈이 발생한 로비 코드
 * @param userIdentifier 이탈한 사용자 식별자
 */
public record PlayerInGameDisconnectEvent(
        String lobbyCode,
        String userIdentifier
) {}
