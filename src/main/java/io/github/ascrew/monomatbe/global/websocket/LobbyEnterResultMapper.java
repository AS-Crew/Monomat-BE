package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;

/**
 * enter_lobby.lua 반환값을 Java 내부 결과 타입으로 변환한다.
 *
 * Lua 반환 문자열과 STOMP ERROR code 매핑을 한 곳에서 관리해 반환값 추가/변경 시 누락 가능성을 줄인다.
 */
final class LobbyEnterResultMapper {

    private static final String ENTER_RESULT_ENTERED = "ENTERED";
    private static final String ENTER_RESULT_ALREADY_JOINED = "ALREADY_JOINED";
    private static final String ENTER_RESULT_SESSION_REPLACED_PREFIX = "SESSION_REPLACED:";
    private static final String ENTER_RESULT_STALE_SESSION_PREFIX = "STALE_SESSION:";
    private static final String ENTER_RESULT_LOBBY_NOT_FOUND = "LOBBY_NOT_FOUND";
    private static final String ENTER_RESULT_INVALID_SEQUENCE = "INVALID_SEQUENCE";
    private static final String ENTER_RESULT_FULL = "FULL";
    private static final String ENTER_RESULT_LOBBY_NOT_WAITING = "LOBBY_NOT_WAITING";
    private static final String ENTER_RESULT_INVALID_LOBBY_CAPACITY = "INVALID_LOBBY_CAPACITY";
    private static final String ENTER_RESULT_KICKED_USER = "KICKED_USER";

    LobbyEnterResultType parse(String result) {
        if (ENTER_RESULT_ENTERED.equals(result)) {
            return LobbyEnterResultType.ENTERED;
        }

        if (ENTER_RESULT_ALREADY_JOINED.equals(result)) {
            return LobbyEnterResultType.ALREADY_JOINED;
        }

        if (result != null && result.startsWith(ENTER_RESULT_SESSION_REPLACED_PREFIX)) {
            return LobbyEnterResultType.SESSION_REPLACED;
        }

        if (result != null && result.startsWith(ENTER_RESULT_STALE_SESSION_PREFIX)) {
            return LobbyEnterResultType.STALE_SESSION;
        }

        if (ENTER_RESULT_LOBBY_NOT_FOUND.equals(result)) {
            return LobbyEnterResultType.LOBBY_NOT_FOUND;
        }

        if (ENTER_RESULT_INVALID_SEQUENCE.equals(result)) {
            return LobbyEnterResultType.INVALID_SEQUENCE;
        }

        if (ENTER_RESULT_FULL.equals(result)) {
            return LobbyEnterResultType.FULL;
        }

        if (ENTER_RESULT_LOBBY_NOT_WAITING.equals(result)) {
            return LobbyEnterResultType.LOBBY_NOT_WAITING;
        }

        if (ENTER_RESULT_INVALID_LOBBY_CAPACITY.equals(result)) {
            return LobbyEnterResultType.INVALID_LOBBY_CAPACITY;
        }

        if (ENTER_RESULT_KICKED_USER.equals(result)) {
            return LobbyEnterResultType.KICKED_USER;
        }

        return LobbyEnterResultType.UNKNOWN;
    }

    enum LobbyEnterResultType {
        ENTERED(null),
        ALREADY_JOINED(null),
        SESSION_REPLACED(null),

        STALE_SESSION(StompErrorCode.LOBBY_STALE_SESSION),
        LOBBY_NOT_FOUND(StompErrorCode.LOBBY_NOT_FOUND),
        INVALID_SEQUENCE(StompErrorCode.LOBBY_INVALID_SEQUENCE),
        FULL(StompErrorCode.LOBBY_FULL),
        LOBBY_NOT_WAITING(StompErrorCode.LOBBY_NOT_WAITING),
        INVALID_LOBBY_CAPACITY(StompErrorCode.LOBBY_INVALID_CAPACITY),
        KICKED_USER(StompErrorCode.LOBBY_KICKED_USER),
        UNKNOWN(StompErrorCode.LOBBY_ENTER_UNKNOWN_RESULT);

        private final StompErrorCode errorCode;

        LobbyEnterResultType(StompErrorCode errorCode) {
            this.errorCode = errorCode;
        }

        boolean isSuccess() {
            return errorCode == null;
        }

        StompErrorCode resolveErrorCode() {
            if (errorCode == null) {
                throw new IllegalStateException("성공 로비 입장 결과에는 STOMP 에러 코드가 없습니다: " + this);
            }

            return errorCode;
        }
    }
}