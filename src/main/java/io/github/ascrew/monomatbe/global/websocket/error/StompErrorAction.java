package io.github.ascrew.monomatbe.global.websocket.error;

/**
 * STOMP ERROR 수신 후 클라이언트가 수행해야 하는 후속 동작.
 *
 * FE가 오류 메시지 문자열을 직접 분석하지 않고,
 * action 값을 기준으로 화면 이동과 복구 정책을 결정할 수 있도록 한다.
 */
public enum StompErrorAction {

    /**
     * 현재 로비 입장을 중단하고 로비 목록 또는 이전 화면으로 복귀한다.
     *
     * 사용 예:
     * - 존재하지 않는 로비
     * - 최대 인원에 도달한 로비
     * - 이미 시작된 로비
     * - 강퇴된 로비
     */
    RETURN_TO_LOBBY_LIST,

    /**
     * 현재 인증 정보로 WebSocket 연결 자체를 다시 시도한다.
     *
     * 사용 예:
     * - CONNECT 단계의 WebSocket 세션 ID 누락
     * - 일시적인 연결 생성 실패
     */
    RETRY_CONNECT,

    /**
     * 현재 화면 상태 또는 서버 상태를 다시 조회한 뒤 재시도한다.
     *
     * 사용 예:
     * - sessionSequence 누락
     * - 일시적인 Redis 또는 Lua 실행 실패
     */
    REFRESH_AND_RETRY,

    /**
     * 현재 WebSocket 연결을 폐기하고 새 연결을 생성한다.
     *
     * 사용 예:
     * - 더 최신 WebSocket 연결이 이미 존재하는 경우
     */
    RECONNECT,

    /**
     * Access Token을 갱신한 후 갱신된 토큰으로 WebSocket을 다시 연결한다.
     *
     * 사용 예:
     * - STOMP CONNECT Access Token 누락
     * - Access Token 만료
     */
    REFRESH_TOKEN,

    /**
     * 현재 인증 정보를 폐기하고 로그인 화면으로 이동한다.
     *
     * 같은 세션으로 재연결해도 해결되지 않는 인증 실패에 사용한다.
     *
     * 사용 예:
     * - 위변조되거나 유효하지 않은 Access Token
     * - 중복 로그인 또는 강제 로그인으로 폐기된 세션
     */
    RELOGIN,

    /**
     * 별도 후속 동작 없이 현재 상태를 유지한다.
     */
    NONE
}