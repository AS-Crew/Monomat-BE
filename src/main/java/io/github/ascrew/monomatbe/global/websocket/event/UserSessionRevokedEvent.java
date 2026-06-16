package io.github.ascrew.monomatbe.global.websocket.event;

import java.util.List;

/**
 * 회원 중복 로그인 차단(force 로그인)으로 기존 활성 세션이 revoke될 때 발행되는 이벤트. (#204)
 *
 * [위치 선정 이유 — global/websocket/event]
 * domain/auth가 이 이벤트를 발행하고, global/websocket의 UserSessionRevokedEventListener가
 * 수신하여 해당 사용자에게 STOMP 세션 종료 알림을 보낸다.
 *
 * 이벤트 객체가 domain/auth에 위치하면 global.websocket 계층이 domain.auth에 의존하게 되어
 * 의존 방향이 역전된다. 따라서 global에 위치시켜 domain -> global 단방향 의존을 유지한다.
 * (PlayerLeaveEvent와 동일한 패턴)
 *
 * @param sessionIds revoke된 세션 식별자 목록. 각 값은 UserSession.sessionId이자
 *                   STOMP CONNECT 시 부여되는 userIdentifier(=STOMP principal name)와 동일하다.
 */
public record UserSessionRevokedEvent(
        List<String> sessionIds
) {}
