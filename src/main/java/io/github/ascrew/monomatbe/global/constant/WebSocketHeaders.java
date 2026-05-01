/*
 * WebSocket / STOMP 통신에서 사용하는 헤더 키 및 세션 속성 키를
 * 중앙에서 관리하는 상수 클래스.
 *
 * [사용 예시]
 * accessor.getFirstNativeHeader(WebSocketHeaders.USER_IDENTIFIER)
 * sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER)
 * sessionAttributes.put(WebSocketHeaders.ROOM_ID, code)
 */
package io.github.ascrew.monomatbe.global.constant;

public final class WebSocketHeaders {

    // 인스턴스화 방지
    private WebSocketHeaders() {}

    /**
     * STOMP CONNECT 헤더 및 세션 속성에서 사용자 식별자를 저장/조회할 때 사용하는 키.
     * 게스트: UUID / 정식 회원: userId
     *
     * 사용 위치:
     * - StompChannelInterceptor : CONNECT 시 헤더에서 추출하여 세션에 저장
     * - ChatController          : 세션에서 발신자 식별자 조회
     * - WebSocketEventListener  : 연결/해제 이벤트에서 식별자 조회
     */
    public static final String USER_IDENTIFIER = "userIdentifier";

    /**
     * 세션 속성에서 현재 참여 중인 로비 코드를 저장/조회할 때 사용하는 키.
     *
     * 사용 위치:
     * - StompChannelInterceptor  : SUBSCRIBE 시 로비 코드 추출하여 세션에 저장
     * - WebSocketEventListener   : DISCONNECT 시 퇴장 처리에 사용
     */
    public static final String ROOM_ID = "roomId";

    /**
     * 사용자 온라인 상태를 Redis에 저장할 때 사용하는 값 상수.
     *
     * 사용 위치:
     * - WebSocketEventListener : CONNECT 이벤트에서 Redis에 상태 저장
     */
    public static final String STATUS_ONLINE = "ONLINE";

    /**
     * 세션에서 사용자 식별자를 찾지 못했을 때 사용하는 폴백 값.
     * 로그 추적 및 방어 코드에 활용됩니다.
     *
     * 사용 위치:
     * - WebSocketEventListener  : 식별자 조회 실패 시
     * - ChatController          : 식별자 조회 실패 시
     */
    public static final String UNKNOWN_IDENTIFIER = "UNKNOWN";
}