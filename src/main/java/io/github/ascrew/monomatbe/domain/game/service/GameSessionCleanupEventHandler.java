package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 로비 폭파 이벤트를 수신하여 게임 세션 Redis 키를 정리하는 핸들러
 *
 * [설계 의도]
 * 로비 폭파는 domain/lobby의 책임이고, 게임 세션 키 정리는 domain/game의 책임이다.
 * 두 도메인을 직접 결합하지 않기 위해 global의 LobbyClosedEvent로 디커플링한다.
 * (global/websocket의 PlayerLeaveEvent -> LobbyLeaveEventHandler 패턴과 동일)
 *
 * 게임 도중 전원 퇴장으로 로비가 폭파되면 게임 세션 키가 orphan으로 남으므로,
 * 이 핸들러가 즉시 삭제(deleteNow)한다. 게임 세션이 없으면 안전하게 no-op이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameSessionCleanupEventHandler {

    private final GameSessionCleanupService gameSessionCleanupService;

    @EventListener
    public void handleLobbyClosed(LobbyClosedEvent event) {
        String lobbyCode = event.lobbyCode();
        if (!StringUtils.hasText(lobbyCode)) {
            return;
        }

        log.info("[handleLobbyClosed] 로비 폭파 감지 - 게임 세션 키 정리 시도. code: {}", lobbyCode);
        gameSessionCleanupService.deleteNow(lobbyCode);
    }
}
