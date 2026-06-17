/*
 * 로비 퇴장 유스케이스 전용 서비스
 *
 * [책임]
 * - leave_lobby.lua 실행 위임 및 결과 분기 처리
 * - 퇴장 결과에 따른 실시간 알림 발행
 * - 명시적 퇴장 요청 처리(LEAVE 시스템 메시지 발행 + ws:connection 키 정리)
 *
 * [설계 의도]
 * 기존에는 퇴장 처리 로직이 WebSocket DISCONNECT 경로(LobbyLeaveEventHandler)에만 존재했다.
 * 그 결과 FE 퇴장 버튼이 WebSocket 연결을 끊지 않으면 BE가 퇴장을 전혀 처리하지 못했다.
 *
 * 강퇴(LobbyKickService)와 동일하게, 퇴장도 명시적 유스케이스로 분리하여
 * STOMP 퇴장 요청과 DISCONNECT 이벤트가 동일한 퇴장 처리 로직을 공유하도록 한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyLeaveService {

    private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

    private final LobbyRepository lobbyRepository;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * leave_lobby.lua를 실행하고 결과에 따라 실시간 알림을 발행한다.
     *
     * [처리 분기]
     * - Destroyed : 마지막 유저 퇴장으로 로비 폭파 -> LobbyClosedEvent + 전역 로비 목록 refresh
     * - Delegated : 방장 퇴장으로 방장 위임 -> HOST_CHANGED 메시지 + 로비 내부 refresh
     * - Left      : 일반 퇴장 -> 로비 내부 refresh
     * - Error     : 처리 실패 -> 브로드캐스트 없이 로그만 기록
     *
     * [주의]
     * 실제 participants/order/ready/세션 키 상태 변경은 Lua에서 원자적으로 처리한다.
     * leave_lobby.lua는 이미 빠진 유저에 대해 멱등하므로 중복 호출되어도 안전하다.
     *
     * @return 퇴장 처리 결과(LEAVE 시스템 메시지 발행 여부 판단 등에 사용)
     */
    public LeaveLobbyResult processLeave(String code, String userIdentifier) {
        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(code, userIdentifier);

        switch (result) {
            case LeaveLobbyResult.Destroyed destroyed -> {
                log.info("[processLeave] 로비 폭파 - 로비: {}", destroyed.lobbyCode());

                /*
                 * 게임 도중 전원 퇴장으로 로비가 폭파되면 게임 세션 Redis 키가 orphan으로 남는다.
                 * 도메인 간 직접 결합을 피하기 위해 LobbyClosedEvent로 game 도메인에 정리를 위임한다.
                 *
                 * [순서·격리] 내부 정합성(세션 정리) 이벤트를 실시간 STOMP 브로드캐스트보다 먼저 발행하고,
                 * 브로드캐스트는 try-catch로 격리한다. STOMP 전송 실패가 정리 이벤트 발행을 막아
                 * Redis에 orphan 세션 키가 영구 잔존하는 것을 방지한다.
                 */
                eventPublisher.publishEvent(new LobbyClosedEvent(destroyed.lobbyCode()));

                try {
                    lobbyRealtimeNotifier.notifyLobbyListRefresh();
                } catch (Exception e) {
                    log.warn(
                            "[processLeave] 로비 폭파 목록 갱신 알림 실패 - 로비: {}",
                            destroyed.lobbyCode(),
                            e
                    );
                }
            }

            case LeaveLobbyResult.Delegated delegated -> {
                log.info(
                        "[processLeave] 방장 위임 - 로비: {}, 새 방장: {}",
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
                        "[processLeave] 일반 퇴장 - 로비: {}, 식별자: {}",
                        left.lobbyCode(),
                        left.userId()
                );
                lobbyRealtimeNotifier.notifyLobbyInfoRefresh(left.lobbyCode());
            }

            case LeaveLobbyResult.Error error -> {
                log.error("[processLeave] 퇴장 처리 실패 - 사유: {}", error.reason());
            }
        }

        return result;
    }

    /**
     * FE 퇴장 버튼 등 클라이언트의 명시적 퇴장 요청을 처리한다.
     *
     * [처리 흐름]
     * 1. 로비 코드, 요청자 식별자 검증
     * 2. processLeave로 Redis 상태 변경 및 실시간 알림 발행
     * 3. 정상 퇴장(Left)/방장 위임(Delegated)인 경우 LEAVE 시스템 메시지 발행
     * 4. 요청한 WebSocket 세션의 ws:connection 키 정리
     *
     * [ws:connection 키 정리 이유]
     * 명시적 퇴장은 전역 STOMP 연결을 유지한 채 발생할 수 있다.
     * ws:connection:{wsSessionId} 키를 미리 삭제하면 이후 실제 DISCONNECT 발생 시
     * WebSocketEventListener.findLobbyCodeByWsSessionId가 null을 반환하여
     * 이미 처리된 퇴장이 다시 처리(중복 브로드캐스트)되는 것을 방지한다.
     */
    public void leaveByRequest(String code, String userIdentifier, String wsSessionId) {
        if (!StringUtils.hasText(code) || !LOBBY_CODE_PATTERN.matcher(code).matches()) {
            log.warn("[leaveByRequest] 유효하지 않은 로비 코드 - code: {}", code);
            return;
        }

        if (!StringUtils.hasText(userIdentifier)) {
            log.warn("[leaveByRequest] 식별자 없는 퇴장 요청 무시 - 로비: {}", code);
            return;
        }

        LeaveLobbyResult result = processLeave(code, userIdentifier);

        if (result instanceof LeaveLobbyResult.Left
                || result instanceof LeaveLobbyResult.Delegated) {
            boolean leaveMessagePublished = lobbyRealtimeNotifier.notifyLeaveMessage(code, userIdentifier);

            if (!leaveMessagePublished) {
                log.error(
                        "LEAVE 메시지 전송 실패 - Redis 퇴장 상태는 반영되었으나 클라이언트 알림이 누락될 수 있음. "
                                + "lobbyCode: {}, userIdentifier: {}",
                        code,
                        userIdentifier
                );
            }
        }

        deleteWsConnection(wsSessionId);
    }

    /**
     * 명시적 퇴장 요청을 보낸 WebSocket 세션의 ws:connection 키를 삭제한다.
     */
    private void deleteWsConnection(String wsSessionId) {
        if (!StringUtils.hasText(wsSessionId)) {
            return;
        }

        try {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        } catch (Exception e) {
            log.warn("퇴장 요청 후 ws:connection 키 삭제 실패 - wsSessionId: {}", wsSessionId, e);
        }
    }
}
