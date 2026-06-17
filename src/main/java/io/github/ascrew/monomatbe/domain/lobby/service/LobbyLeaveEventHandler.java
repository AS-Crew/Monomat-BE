/*
 * 로비 퇴장 이벤트 처리 전용 핸들러
 *
 * [책임]
 * - PlayerLeaveEvent 수신
 * - leave_lobby.lua 실행 위임
 * - 퇴장 결과에 따른 실시간 알림 발행
 * - 방장 위임 시 HOST_CHANGED 시스템 메시지 발행
 *
 * [설계 의도]
 * WebSocket DISCONNECT 자체는 global.websocket 계층에서 감지한다.
 * 하지만 "로비에서 나간다"는 도메인 상태 변경이므로
 * PlayerLeaveEvent를 통해 lobby 도메인으로 전달된다.
 *
 * 이 핸들러는 해당 도메인 이벤트를 받아 leave_lobby.lua를 실행하고,
 * 결과에 따라 로비 목록 refresh 또는 로비 내부 refresh를 발행한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyLeaveEventHandler {

    private final LobbyLeaveService lobbyLeaveService;

    /**
     * 플레이어 퇴장 이벤트(WebSocket DISCONNECT)를 수신하여 퇴장 처리를 위임한다.
     *
     * [주의]
     * DISCONNECT 경로의 LEAVE 시스템 메시지 발행과 세션 키 정리는
     * WebSocketEventListener가 담당하므로, 여기서는 퇴장 상태 변경과
     * 실시간 알림만 LobbyLeaveService.processLeave에 위임한다.
     */
    @EventListener
    public void handlePlayerLeave(PlayerLeaveEvent event) {
        String code = event.lobbyCode();
        String userIdentifier = event.userIdentifier();

        if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) {
            return;
        }

        lobbyLeaveService.processLeave(code, userIdentifier);
    }
}