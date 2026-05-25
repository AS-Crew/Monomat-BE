package io.github.ascrew.monomatbe.global.websocket.error;

import java.time.Instant;

/**
 * STOMP ERROR 프레임의 body에 담을 표준 payload
 *
 * [응답 예시]
 * {
 *   "type": "STOMP_ERROR",
 *   "code": "LOBBY_NOT_FOUND",
 *   "message": "존재하지 않는 로비입니다.",
 *   "action": "RETURN_TO_LOBBY_LIST",
 *   "recoverable": false,
 *   "timestamp": "2026-05-25T00:00:00Z"
 * }
 */
public record StompErrorPayload(
        String type,
        String code,
        String message,
        String action,
        boolean recoverable,
        String timestamp
) {

    private static final String TYPE = "STOMP_ERROR";

    public static StompErrorPayload from(StompErrorException exception) {
        StompErrorCode errorCode = exception.getErrorCode();

        return new StompErrorPayload(
                TYPE,
                errorCode.name(),
                exception.getClientMessage(),
                errorCode.getAction().name(),
                errorCode.isRecoverable(),
                Instant.now().toString()
        );
    }

    public static StompErrorPayload from(StompErrorCode errorCode) {
        return new StompErrorPayload(
                TYPE,
                errorCode.name(),
                errorCode.getDefaultMessage(),
                errorCode.getAction().name(),
                errorCode.isRecoverable(),
                Instant.now().toString()
        );
    }
}