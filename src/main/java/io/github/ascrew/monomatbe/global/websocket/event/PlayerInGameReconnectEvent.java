package io.github.ascrew.monomatbe.global.websocket.event;

/**
 * 인게임(PLAYING 상태)에서 플레이어가 WebSocket 재연결(복귀)했을 때 발행되는 이벤트.
 *
 * @param lobbyCode      복귀가 발생한 로비 코드
 * @param userIdentifier 복귀한 사용자 식별자
 */
public record PlayerInGameReconnectEvent(
        String lobbyCode,
        String userIdentifier
) {}
