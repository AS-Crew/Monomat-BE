package org.example.monomatbe.global.exception;

/**
 * 프로젝트 전역 비즈니스 예외
 *
 * 모든 사용자 정의 예외는 이 클래스를 상속받아야 합니다.
 */
public class BusinessException extends RuntimeException {

    private final int errorCode;

    public BusinessException(String message) {
        super(message);
        this.errorCode = 400;
    }

    public BusinessException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 400;
    }

    public BusinessException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}

