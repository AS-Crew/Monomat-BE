package io.github.ascrew.monomatbe.global.websocket.error;

/**
 * WebSocket / STOMP 실패 코드를 중앙 관리한다.
 *
 * [중요]
 * FE는 message 문자열이 아니라 code/action/recoverable을 기준으로 분기해야 한다.
 * 따라서 enum 이름은 API 계약으로 보고 함부로 변경하지 않는다.
 */
public enum StompErrorCode {

    /**
     * STOMP CONNECT 시 Authorization 헤더가 없거나 Bearer 형식이 아닌 경우. (#211)
     *
     * Access Token 없이는 WebSocket 연결을 인증할 수 없으므로 재로그인이 필요하다.
     */
    ACCESS_TOKEN_MISSING(
            "인증 토큰이 없습니다. 다시 로그인 후 접속해주세요.",
            StompErrorAction.RELOGIN,
            true
    ),

    /**
     * Access Token 서명이 유효하지 않거나, 필수 claim 누락, 블랙리스트 등록 등으로
     * 신뢰할 수 없는 토큰인 경우. (#211)
     */
    ACCESS_TOKEN_INVALID(
            "유효하지 않은 인증 토큰입니다. 다시 로그인 후 접속해주세요.",
            StompErrorAction.RELOGIN,
            true
    ),

    /**
     * Access Token이 만료된 경우. (#211)
     *
     * FE는 Refresh Token으로 재발급한 뒤 재연결하거나 재로그인해야 한다.
     */
    ACCESS_TOKEN_EXPIRED(
            "인증 토큰이 만료되었습니다. 토큰 재발급 후 다시 접속해주세요.",
            StompErrorAction.RELOGIN,
            true
    ),

    /**
     * 더 최신 WebSocket 세션이 이미 존재하는 경우. (#211)
     *
     * 현재 최신 세션 판별은 로비 레벨(enter_lobby.lua / lobbyUserSessionKey)에서 수행되며,
     * 본 코드는 FE 계약 정합을 위해 정의한다.
     */
    STALE_SESSION(
            "더 최신 WebSocket 세션이 이미 존재합니다. 다시 접속해주세요.",
            StompErrorAction.RECONNECT,
            true
    ),

    CONNECT_USER_IDENTIFIER_MISSING(
            "사용자 식별자가 없습니다. 다시 로그인 후 접속해주세요.",
            StompErrorAction.RETRY_CONNECT,
            true
    ),

    CONNECT_USER_IDENTIFIER_INVALID(
            "유효하지 않은 사용자 식별자입니다. 다시 로그인 후 접속해주세요.",
            StompErrorAction.RETRY_CONNECT,
            true
    ),

    CONNECT_SESSION_SEQUENCE_FAILED(
            "WebSocket 세션 생성에 실패했습니다. 다시 접속해주세요.",
            StompErrorAction.RETRY_CONNECT,
            true
    ),

    CONNECT_WS_SESSION_ID_MISSING(
            "WebSocket 세션 ID가 없습니다. 다시 접속해주세요.",
            StompErrorAction.RETRY_CONNECT,
            true
    ),

    CONNECT_ONLINE_STATUS_FAILED(
            "사용자 온라인 상태 저장에 실패했습니다. 잠시 후 다시 접속해주세요.",
            StompErrorAction.RETRY_CONNECT,
            true
    ),

    CONNECT_SESSION_REVOKED(
            "세션이 만료되었거나 다른 기기에서 로그인되었습니다. 다시 로그인 후 접속해주세요.",
            StompErrorAction.RELOGIN,
            true
    ),

    SESSION_UNAUTHENTICATED(
            "인증 정보가 존재하지 않습니다. 다시 접속해주세요.",
            StompErrorAction.RETRY_CONNECT,
            true
    ),

    SESSION_REVOKED(
            "세션이 만료되었거나 다른 기기에서 로그인되었습니다. 다시 로그인 후 접속해주세요.",
            StompErrorAction.RELOGIN,
            true
    ),

    LOBBY_ENTER_WS_SESSION_MISSING(
            "로비 입장에 필요한 WebSocket 세션 ID가 없습니다. 다시 접속해주세요.",
            StompErrorAction.RECONNECT,
            true
    ),

    LOBBY_ENTER_SESSION_ATTRIBUTES_MISSING(
            "로비 입장에 필요한 세션 정보가 없습니다. 새로고침 후 다시 시도해주세요.",
            StompErrorAction.REFRESH_AND_RETRY,
            true
    ),

    LOBBY_ENTER_SEQUENCE_MISSING(
            "WebSocket 세션 순서 정보가 없습니다. 새로고침 후 다시 시도해주세요.",
            StompErrorAction.REFRESH_AND_RETRY,
            true
    ),

    LOBBY_NOT_FOUND(
            "존재하지 않는 로비입니다.",
            StompErrorAction.RETURN_TO_LOBBY_LIST,
            false
    ),

    LOBBY_FULL(
            "로비 최대 인원에 도달했습니다.",
            StompErrorAction.RETURN_TO_LOBBY_LIST,
            false
    ),

    LOBBY_NOT_WAITING(
            "이미 시작되었거나 입장할 수 없는 로비입니다.",
            StompErrorAction.RETURN_TO_LOBBY_LIST,
            false
    ),

    LOBBY_INVALID_CAPACITY(
            "로비 정원 정보가 유효하지 않습니다.",
            StompErrorAction.RETURN_TO_LOBBY_LIST,
            false
    ),

    LOBBY_STALE_SESSION(
            "더 최신 WebSocket 세션이 이미 존재합니다. 다시 접속해주세요.",
            StompErrorAction.RECONNECT,
            true
    ),

    LOBBY_KICKED_USER(
            "강퇴된 로비에는 재입장할 수 없습니다.",
            StompErrorAction.RETURN_TO_LOBBY_LIST,
            false
    ),

    LOBBY_INVALID_SEQUENCE(
            "로비 입장 세션 상태가 유효하지 않습니다. 새로고침 후 다시 시도해주세요.",
            StompErrorAction.REFRESH_AND_RETRY,
            true
    ),

    LOBBY_ENTER_UNKNOWN_RESULT(
            "로비 입장 중 알 수 없는 서버 응답이 발생했습니다. 새로고침 후 다시 시도해주세요.",
            StompErrorAction.REFRESH_AND_RETRY,
            true
    ),

    LOBBY_ENTER_TEMPORARILY_UNAVAILABLE(
            "일시적으로 로비 입장 상태를 확인할 수 없습니다. 새로고침 후 다시 시도해주세요.",
            StompErrorAction.REFRESH_AND_RETRY,
            true
    ),

    INTERNAL_STOMP_ERROR(
            "WebSocket 처리 중 서버 오류가 발생했습니다.",
            StompErrorAction.REFRESH_AND_RETRY,
            true
    );

    private final String defaultMessage;
    private final StompErrorAction action;
    private final boolean recoverable;

    StompErrorCode(
            String defaultMessage,
            StompErrorAction action,
            boolean recoverable
    ) {
        this.defaultMessage = defaultMessage;
        this.action = action;
        this.recoverable = recoverable;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public StompErrorAction getAction() {
        return action;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}