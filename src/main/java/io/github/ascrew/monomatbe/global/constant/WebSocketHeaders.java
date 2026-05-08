/*
 * WebSocket / STOMP 통신에서 사용하는 헤더 키 및 세션 속성 키를
 * 중앙에서 관리하는 상수 클래스.
 */
package io.github.ascrew.monomatbe.global.constant;

public final class WebSocketHeaders {

    // 인스턴스화 방지
    private WebSocketHeaders() {}

    /**
     * STOMP CONNECT 헤더 및 세션 속성에서 사용자 식별자를 저장/조회할 때 사용하는 키.
     *
     * 게스트: UUID
     * 회원: userId
     *
     * 사용 위치:
     * - StompChannelInterceptor : CONNECT 시 헤더에서 추출하여 세션에 저장
     * - ChatService             : 세션에서 발신자 식별자 조회
     * - WebSocketEventListener  : 연결/해제 이벤트에서 식별자 조회
     */
    public static final String USER_IDENTIFIER = "userIdentifier";

    /**
     * 세션 속성에서 현재 참여 중인 로비 코드를 저장/조회할 때 사용하는 키.
     *
     * 사용 위치:
     * - StompChannelInterceptor : SUBSCRIBE 시 로비 코드 추출하여 세션에 저장
     * - WebSocketEventListener  : DISCONNECT 시 퇴장 처리에 사용
     */
    public static final String ROOM_ID = "roomId";

    /**
     * 로비 입장 Lua 처리 결과를 STOMP 세션 속성에 임시 저장할 때 사용하는 키.
     *
     * 사용 위치:
     * - StompChannelInterceptor : SUBSCRIBE preSend 단계에서 enter_lobby.lua 실행 후 저장
     * - WebSocketEventListener  : SessionSubscribeEvent에서 후처리 시 조회
     */
    public static final String LOBBY_ENTER_RESULT = "lobbyEnterResult";

    /**
     * WebSocket 세션 생성 순서값을 STOMP 세션 속성에 저장할 때 사용하는 키.
     *
     * 동일 userIdentifier의 재접속이 겹칠 때,
     * 오래된 세션이 최신 세션을 덮어쓰지 않도록 Lua에서 비교하는 데 사용한다.
     */
    public static final String SESSION_SEQUENCE = "sessionSequence";

    /**
     * 사용자 온라인 상태를 Redis에 저장할 때 사용하는 값 상수.
     *
     * 사용 위치:
     * - StompChannelInterceptor : CONNECT 시 Redis에 상태 저장
     */
    public static final String STATUS_ONLINE = "ONLINE";

    /**
     * 세션에서 사용자 식별자를 찾지 못했을 때 사용하는 폴백 값.
     *
     * 사용 위치:
     * - WebSocketEventListener : 식별자 조회 실패 시
     * - ChatService            : 식별자 조회 실패 시
     */
    public static final String UNKNOWN_IDENTIFIER = "UNKNOWN";

    // =========================================================
    // Redis ws:connection Hash 필드 키 상수
    // =========================================================

    /**
     * Redis ws:connection:{wsSessionId} Hash의 사용자 식별자 필드 키.
     *
     * 필드명은 "userId"이지만 저장 값은 DB PK가 아니라 userIdentifier입니다.
     *
     * 사용 위치:
     * - enter_lobby.lua             : 저장 시
     * - WebSocketEventListener      : DISCONNECT 조회 시
     * - StompChannelInterceptor     : enter_lobby.lua ARGV 전달 시
     */
    public static final String SESSION_USER_ID = "userId";

    /**
     * Redis ws:connection:{wsSessionId} Hash의 로비 코드 필드 키.
     *
     * 사용 위치:
     * - enter_lobby.lua             : 저장 시
     * - WebSocketEventListener      : DISCONNECT 조회 시
     * - StompChannelInterceptor     : enter_lobby.lua ARGV 전달 시
     */
    public static final String SESSION_LOBBY_CODE = "lobbyCode";
}