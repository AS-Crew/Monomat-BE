package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.Map;

/**
 * WebSocket / STOMP 세션에서 공통으로 사용되는 유틸리티 메서드 모음.
 *
 * [설계 이유]
 * ChatService와 WebSocketEventListener 모두 세션에서 사용자 식별자를 추출하는 동일한 로직이 필요
 * 중복을 제거하고 정책 변경 시 단일 지점만 수정하도록
 * 정적 유틸 클래스로 추출합니다.
 *
 * [인스턴스화 방지]
 * 모든 메서드가 정적이므로 생성자를 private으로 막습니다.
 */
public final class WebSocketSessionUtils {

    private WebSocketSessionUtils() {}

    /**
     * STOMP 헤더 접근자(SimpMessageHeaderAccessor)에서 사용자 식별자를 추출한다.
     * ChatService에서 사용하는 시그니처이다.
     *
     * [동작]
     * 1. 세션 속성 Map을 조회합니다.
     * 2. StompChannelInterceptor가 CONNECT 시점에 저장한 USER_IDENTIFIER 키로 값을 꺼냅니다.
     * 3. 값이 없으면 UNKNOWN_IDENTIFIER를 반환하여 NPE를 방지합니다.
     *
     * @param accessor STOMP 메시지 헤더 접근자
     * @return 사용자 식별자 (게스트 UUID 또는 회원 ID), 없으면 UNKNOWN_IDENTIFIER
     */
    public static String extractUserIdentifier(SimpMessageHeaderAccessor accessor) {
        // 세션 속성 자체가 null인 경우 방어 처리
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        return extractFromAttributes(sessionAttributes);
    }

    /**
     * 세션 속성 Map에서 직접 사용자 식별자를 추출합니다.
     * WebSocketEventListener처럼 이미 Map을 가지고 있는 경우에 사용하는 시그니처입니다.
     *
     * @param sessionAttributes WebSocket 세션 속성 Map (null 허용)
     * @return 사용자 식별자 (게스트 UUID 또는 회원 ID), 없으면 UNKNOWN_IDENTIFIER
     */
    public static String extractUserIdentifier(Map<String, Object> sessionAttributes) {
        return extractFromAttributes(sessionAttributes);
    }

    /**
     * 실제 추출 로직을 담당하는 내부 메서드.
     * 두 public 메서드가 공유하는 핵심 로직을 단일 지점으로 관리합니다.
     *
     * @param sessionAttributes WebSocket 세션 속성 Map (null 허용)
     * @return 사용자 식별자, 없으면 UNKNOWN_IDENTIFIER
     */
    private static String extractFromAttributes(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return WebSocketHeaders.UNKNOWN_IDENTIFIER;
        }

        Object identifier = sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);

        // identifier가 null이면 UNKNOWN_IDENTIFIER로 폴백하여 NPE 방지
        return identifier != null
                ? (String) identifier
                : WebSocketHeaders.UNKNOWN_IDENTIFIER;
    }
}