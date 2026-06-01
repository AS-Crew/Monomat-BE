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

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyLeaveEventHandler {

    private final LobbyRepository lobbyRepository;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 플레이어 퇴장 이벤트를 수신하여 퇴장 처리를 수행한다.
     *
     * [처리 분기]
     * - Destroyed : 마지막 유저 퇴장으로 로비 폭파 -> 전역 로비 목록 refresh
     * - Delegated : 방장 퇴장으로 방장 위임 -> HOST_CHANGED 메시지 + 로비 내부 refresh
     * - Left      : 일반 퇴장 -> 로비 내부 refresh
     * - Error     : 처리 실패 -> 브로드캐스트 없이 로그만 기록
     */
    @EventListener
    public void handlePlayerLeave(PlayerLeaveEvent event) {
        String code = event.lobbyCode();
        String userIdentifier = event.userIdentifier();

        if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) {
            return;
        }

        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(code, userIdentifier);

        switch (result) {
            case LeaveLobbyResult.Destroyed destroyed -> {
                log.info("[handlePlayerLeave] 로비 폭파 - 로비: {}", destroyed.lobbyCode());
                lobbyRealtimeNotifier.notifyLobbyListRefresh();

                /*
                 * 게임 도중 전원 퇴장으로 로비가 폭파되면 게임 세션 Redis 키가 orphan으로 남는다.
                 * 도메인 간 직접 결합을 피하기 위해 LobbyClosedEvent로 game 도메인에 정리를 위임한다.
                 */
                eventPublisher.publishEvent(new LobbyClosedEvent(destroyed.lobbyCode()));
            }

            case LeaveLobbyResult.Delegated delegated -> {
                log.info(
                        "[handlePlayerLeave] 방장 위임 - 로비: {}, 새 방장: {}",
                        delegated.lobbyCode(),
                        delegated.newHostId()
                );

                boolean messagePublished = lobbyRealtimeNotifier.notifyHostChangedMessage(
                        delegated.lobbyCode(),
                        delegated.newHostId()
                );

                if (!messagePublished) {
                    log.error(
                            "[ALERT_REQUIRED] HOST_CHANGED 메시지 발행 실패 - code: {}, newHostId: {}",
                            delegated.lobbyCode(),
                            delegated.newHostId()
                    );
                }

                lobbyRealtimeNotifier.notifyLobbyInfoRefresh(delegated.lobbyCode());
            }

            case LeaveLobbyResult.Left left -> {
                log.info(
                        "[handlePlayerLeave] 일반 퇴장 - 로비: {}, 식별자: {}",
                        left.lobbyCode(),
                        left.userId()
                );
                lobbyRealtimeNotifier.notifyLobbyInfoRefresh(left.lobbyCode());
            }

            case LeaveLobbyResult.Error error -> {
                log.error("[handlePlayerLeave] 퇴장 처리 실패 - 사유: {}", error.reason());
            }
        }
    }
}