/*
 * WebSocket / STOMP 통신에서 사용하는 세션 속성 키와
 * Redis Hash 필드 키를 중앙에서 관리하는 상수 클래스.
 */
package io.github.ascrew.monomatbe.global.constant;

public final class WebSocketHeaders {

    private WebSocketHeaders() {
    }

    /**
     * 검증된 Access Token에서 추출한 사용자 식별자를
     * STOMP 세션 속성에 저장하고 조회할 때 사용하는 키.
     *
     * 클라이언트가 STOMP native header에 동일한 이름의 값을 전달하더라도
     * 해당 값은 인증 근거로 사용하지 않는다.
     *
     * 게스트와 회원 모두 인증 세션 UUID인 userIdentifier를 저장한다.
     *
     * 사용 위치:
     * - StompChannelInterceptor : JWT 검증 결과를 세션에 저장
     * - ChatService             : 세션에서 발신자 식별자 조회
     * - WebSocketEventListener  : 연결 및 해제 이벤트에서 식별자 조회
     */
    public static final String USER_IDENTIFIER = "userIdentifier";

    /**
     * 세션 속성에서 현재 참여 중인 로비 코드를 저장하고 조회할 때 사용하는 키.
     *
     * 사용 위치:
     * - StompChannelInterceptor : SUBSCRIBE 시 로비 코드 추출 및 저장
     * - WebSocketEventListener  : DISCONNECT 시 퇴장 처리
     */
    public static final String ROOM_ID = "roomId";

    /**
     * 로비 입장 Lua 처리 결과를 STOMP 세션 속성에 임시 저장할 때 사용하는 키.
     *
     * 사용 위치:
     * - StompChannelInterceptor : SUBSCRIBE 전 enter_lobby.lua 실행 결과 저장
     * - WebSocketEventListener  : SessionSubscribeEvent 후처리
     */
    public static final String LOBBY_ENTER_RESULT = "lobbyEnterResult";

    /**
     * WebSocket 세션 생성 순서값을 STOMP 세션 속성에 저장할 때 사용하는 키.
     *
     * 동일 userIdentifier의 재접속이 겹칠 때
     * 오래된 연결이 최신 연결을 덮어쓰지 않도록 Lua에서 비교한다.
     */
    public static final String SESSION_SEQUENCE = "sessionSequence";

    /**
     * 사용자 온라인 상태를 Redis에 저장할 때 사용하는 값.
     */
    public static final String STATUS_ONLINE = "ONLINE";

    /**
     * 세션에서 사용자 식별자를 찾지 못했을 때 사용하는 폴백 값.
     */
    public static final String UNKNOWN_IDENTIFIER = "UNKNOWN";

    // =========================================================
    // Redis ws:connection Hash 필드 키
    // =========================================================

    /**
     * Redis ws:connection:{wsSessionId} Hash의 사용자 식별자 필드.
     *
     * 필드명은 userId이지만 실제 저장 값은 DB PK가 아니라
     * 인증 세션 UUID인 userIdentifier이다.
     *
     * 사용 위치:
     * - enter_lobby.lua
     * - WebSocketEventListener
     * - StompChannelInterceptor
     */
    public static final String SESSION_USER_ID = "userId";

    /**
     * Redis ws:connection:{wsSessionId} Hash의 로비 코드 필드.
     *
     * 사용 위치:
     * - enter_lobby.lua
     * - WebSocketEventListener
     * - StompChannelInterceptor
     */
    public static final String SESSION_LOBBY_CODE = "lobbyCode";
}