/*
 * WebSocket / STOMP 통신에서 사용하는 헤더 키 및 세션 속성 키를
 * 중앙에서 관리하는 상수 클래스.
 *
 * [수정]
 * SESSION_USER_ID, SESSION_LOBBY_CODE 상수 추가
 * LobbyEventService.saveConnectionInfo()에서 저장할 때와
 * WebSocketEventListener.handleDisconnectEvent()에서 조회할 때
 * 동일한 문자열 리터럴("userId", "lobbyCode")을 각자 하드코딩하고 있었습니다.
 * 상수로 통일하여 오타 시 컴파일 타임에 감지되도록 합니다.
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
     * - WebSocketEventListener : 식별자 조회 실패 시
     * - ChatService            : 식별자 조회 실패 시
     */
    public static final String UNKNOWN_IDENTIFIER = "UNKNOWN";

    // =========================================================
    // Redis ws:connection Hash 필드 키 상수
    //
    // [추가 이유]
    // LobbyEventService.saveConnectionInfo()에서 저장 시 사용하는 "userId", "lobbyCode"와
    // WebSocketEventListener.handleDisconnectEvent()에서 조회 시 사용하는 "userId", "lobbyCode"가
    // 각자 문자열 리터럴로 하드코딩되어 있었습니다.
    // 오타 발생 시 런타임에서야 발견되는 문제를 상수 통일로 컴파일 타임 감지로 전환합니다.
    // =========================================================

    /**
     * Redis ws:connection:{wsSessionId} Hash의 사용자 ID 필드 키.
     *
     * 사용 위치:
     * - LobbyEventService.saveConnectionInfo()     : 저장 시
     * - WebSocketEventListener.handleDisconnectEvent() : 조회 시
     */
    public static final String SESSION_USER_ID = "userId";

    /**
     * Redis ws:connection:{wsSessionId} Hash의 로비 코드 필드 키.
     *
     * 사용 위치:
     * - LobbyEventService.saveConnectionInfo()         : 저장 시
     * - WebSocketEventListener.handleDisconnectEvent() : 조회 시
     */
    public static final String SESSION_LOBBY_CODE = "lobbyCode";
}