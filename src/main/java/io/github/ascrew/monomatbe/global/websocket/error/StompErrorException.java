package io.github.ascrew.monomatbe.global.websocket.error;

import java.util.Objects;

/**
 * STOMP 처리 중 의도적으로 클라이언트에게 표준 ERROR payload를 내려야 할 때 사용하는 예외이다.
 *
 * [설계 의도]
 * IllegalArgumentException / IllegalStateException의 문자열 메시지에 의존하면
 * FE가 안정적으로 에러를 분기할 수 없다.
 *
 * 따라서 code/action/recoverable을 가진 StompErrorCode를 함께 전달한다.
 */
public class StompErrorException extends RuntimeException {

    private final StompErrorCode errorCode;
    private final String clientMessage;

    public StompErrorException(StompErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public StompErrorException(StompErrorCode errorCode, Throwable cause) {
        this(errorCode, null, cause);
    }

    public StompErrorException(
            StompErrorCode errorCode,
            String clientMessage
    ) {
        this(errorCode, clientMessage, null);
    }

    public StompErrorException(
            StompErrorCode errorCode,
            String clientMessage,
            Throwable cause
    ) {
        super(resolveClientMessage(errorCode, clientMessage), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.clientMessage = resolveClientMessage(errorCode, clientMessage);
    }

    public StompErrorCode getErrorCode() {
        return errorCode;
    }

    public String getClientMessage() {
        return clientMessage;
    }

    /**
     * 클라이언트에게 내려줄 메시지를 결정한다.
     *
     * 명시 메시지가 있으면 해당 메시지를 사용하고,
     * 없으면 StompErrorCode의 기본 메시지를 사용한다.
     */
    private static String resolveClientMessage(
            StompErrorCode errorCode,
            String clientMessage
    ) {
        StompErrorCode resolvedErrorCode = Objects.requireNonNull(
                errorCode,
                "errorCode must not be null"
        );

        if (clientMessage == null || clientMessage.isBlank()) {
            return resolvedErrorCode.getDefaultMessage();
        }

        return clientMessage;
    }
}