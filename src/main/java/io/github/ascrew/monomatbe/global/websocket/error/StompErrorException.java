package io.github.ascrew.monomatbe.global.websocket.error;

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
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public StompErrorException(StompErrorCode errorCode, Throwable cause) {
        this(errorCode, errorCode.getDefaultMessage(), cause);
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
        super(clientMessage, cause);
        this.errorCode = errorCode;
        this.clientMessage = clientMessage;
    }

    public StompErrorCode getErrorCode() {
        return errorCode;
    }

    public String getClientMessage() {
        return clientMessage;
    }
}