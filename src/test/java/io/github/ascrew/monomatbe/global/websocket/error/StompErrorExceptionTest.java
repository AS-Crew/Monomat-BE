package io.github.ascrew.monomatbe.global.websocket.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class StompErrorExceptionTest {

    @Test
    @DisplayName("errorCode가 null이면 예외 생성 시점에 차단한다")
    void constructor_withNullErrorCode_throwsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StompErrorException(null))
                .withMessage("errorCode must not be null");
    }

    @Test
    @DisplayName("clientMessage가 null이면 errorCode 기본 메시지를 사용한다")
    void constructor_withNullClientMessage_usesDefaultMessage() {
        // when
        StompErrorException exception = new StompErrorException(
                StompErrorCode.LOBBY_FULL,
                (String) null
        );

        // then
        assertThat(exception.getErrorCode()).isEqualTo(StompErrorCode.LOBBY_FULL);
        assertThat(exception.getClientMessage()).isEqualTo("로비 최대 인원에 도달했습니다.");
        assertThat(exception.getMessage()).isEqualTo("로비 최대 인원에 도달했습니다.");
    }

    @Test
    @DisplayName("clientMessage가 blank이면 errorCode 기본 메시지를 사용한다")
    void constructor_withBlankClientMessage_usesDefaultMessage() {
        // when
        StompErrorException exception = new StompErrorException(
                StompErrorCode.LOBBY_NOT_FOUND,
                "   "
        );

        // then
        assertThat(exception.getErrorCode()).isEqualTo(StompErrorCode.LOBBY_NOT_FOUND);
        assertThat(exception.getClientMessage()).isEqualTo("존재하지 않는 로비입니다.");
        assertThat(exception.getMessage()).isEqualTo("존재하지 않는 로비입니다.");
    }

    @Test
    @DisplayName("clientMessage가 명시되면 명시 메시지를 사용한다")
    void constructor_withCustomClientMessage_usesCustomMessage() {
        // when
        StompErrorException exception = new StompErrorException(
                StompErrorCode.INTERNAL_STOMP_ERROR,
                "커스텀 에러 메시지"
        );

        // then
        assertThat(exception.getErrorCode()).isEqualTo(StompErrorCode.INTERNAL_STOMP_ERROR);
        assertThat(exception.getClientMessage()).isEqualTo("커스텀 에러 메시지");
        assertThat(exception.getMessage()).isEqualTo("커스텀 에러 메시지");
    }

    @Test
    @DisplayName("cause를 전달하면 원인 예외를 유지한다")
    void constructor_withCause_keepsCause() {
        // given
        RuntimeException cause = new RuntimeException("redis failed");

        // when
        StompErrorException exception = new StompErrorException(
                StompErrorCode.CONNECT_ONLINE_STATUS_FAILED,
                cause
        );

        // then
        assertThat(exception.getErrorCode()).isEqualTo(StompErrorCode.CONNECT_ONLINE_STATUS_FAILED);
        assertThat(exception.getClientMessage()).isEqualTo("사용자 온라인 상태 저장에 실패했습니다. 잠시 후 다시 접속해주세요.");
        assertThat(exception.getMessage()).isEqualTo("사용자 온라인 상태 저장에 실패했습니다. 잠시 후 다시 접속해주세요.");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}