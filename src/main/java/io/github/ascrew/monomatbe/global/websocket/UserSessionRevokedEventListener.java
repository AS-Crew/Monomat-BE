package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.websocket.event.SessionRevokedMessage;
import io.github.ascrew.monomatbe.global.websocket.event.UserSessionRevokedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * revoke된 세션 사용자에게 STOMP 세션 종료 알림을 전송한다. (#204)
 *
 * [배경]
 * StompChannelInterceptor의 재검증은 SEND/SUBSCRIBE 같은 인바운드 프레임만 차단한다.
 * 이미 구독이 끝난 기존 탭은 추가 프레임 없이 /topic/... 브로드캐스트를 계속 수신할 수 있다.
 * 서버 측에서 WebSocket 세션을 강제로 close할 수단이 없으므로, force 로그인으로 세션이 revoke될 때
 * 해당 사용자에게 /user/queue/auth로 종료 알림을 보내 FE가 스스로 disconnect/로그아웃하도록 한다.
 *
 * principal name == userIdentifier == UserSession.sessionId 이므로 sessionId를 그대로 사용한다.
 * 미접속 사용자(WebSocket 세션 없음)에게 보내면 라우팅 대상이 없어 no-op이 되므로 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSessionRevokedEventListener {

    private static final String CODE_SESSION_REVOKED = "SESSION_REVOKED";
    private static final String ACTION_RELOGIN = "RELOGIN";
    private static final String MESSAGE =
            "다른 기기에서 로그인되어 현재 세션이 종료되었습니다. 다시 로그인해주세요.";

    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handle(UserSessionRevokedEvent event) {
        if (event.sessionIds() == null || event.sessionIds().isEmpty()) {
            return;
        }

        SessionRevokedMessage payload =
                new SessionRevokedMessage(CODE_SESSION_REVOKED, MESSAGE, ACTION_RELOGIN);

        event.sessionIds().forEach(sessionId -> {
            try {
                messagingTemplate.convertAndSendToUser(
                        sessionId,
                        StompDestinations.SERVER_USER_SESSION_REVOKED,
                        payload
                );
            } catch (RuntimeException e) {
                // 알림 실패가 revoke 트랜잭션 결과(이미 커밋됨)나 다른 사용자 알림을 막지 않도록 격리한다.
                log.warn("세션 종료 알림 전송 실패 - sessionId: {}", sessionId, e);
            }
        });
    }
}
