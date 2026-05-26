package io.github.ascrew.monomatbe.global.websocket.error;

/**
 * STOMP ERROR 수신 후 클라이언트가 수행해야 하는 후속 동작
 *
 * [설계 의도]
 * FE가 에러 메시지 문자열을 파싱하지 않고,
 * action 값만 보고 화면 전환/재시도/새로고침 정책을 결정할 수 있도록 한다.
 */
public enum StompErrorAction {

    /**
     * 현재 로비 입장을 중단하고 로비 목록 또는 이전 화면으로 복귀한다.
     *
     * 사용 예:
     * - 존재하지 않는 로비
     * - 만원 로비
     * - 이미 시작된 로비
     * - 강퇴된 로비
     */
    RETURN_TO_LOBBY_LIST,

    /**
     * WebSocket 연결 자체를 다시 시도한다.
     *
     * 사용 예:
     * - CONNECT 단계에서 세션 ID가 없음
     * - userIdentifier가 누락됨
     */
    RETRY_CONNECT,

    /**
     * 현재 화면 상태를 새로고침한 뒤 다시 시도한다.
     *
     * 사용 예:
     * - sessionSequence 누락
     * - 일시적 Redis/Lua 실패
     */
    REFRESH_AND_RETRY,

    /**
     * 더 최신 WebSocket 세션이 이미 있으므로 현재 세션은 폐기하고 재연결한다.
     */
    RECONNECT,

    /**
     * 별도 후속 동작 없이 현재 상태를 유지한다.
     */
    NONE
}