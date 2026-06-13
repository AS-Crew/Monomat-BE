package io.github.ascrew.monomatbe.global.websocket.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StompErrorCodeContractTest {

    @Test
    @DisplayName("로비 없음은 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyNotFound_contract() {
        // given
        StompErrorCode code = StompErrorCode.LOBBY_NOT_FOUND;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RETURN_TO_LOBBY_LIST);
        assertThat(code.isRecoverable()).isFalse();
        assertThat(code.getDefaultMessage()).isEqualTo("존재하지 않는 로비입니다.");
    }

    @Test
    @DisplayName("로비 만원은 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyFull_contract() {
        // given
        StompErrorCode code = StompErrorCode.LOBBY_FULL;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RETURN_TO_LOBBY_LIST);
        assertThat(code.isRecoverable()).isFalse();
        assertThat(code.getDefaultMessage()).isEqualTo("로비 최대 인원에 도달했습니다.");
    }

    @Test
    @DisplayName("이미 시작된 로비는 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyNotWaiting_contract() {
        // given
        StompErrorCode code = StompErrorCode.LOBBY_NOT_WAITING;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RETURN_TO_LOBBY_LIST);
        assertThat(code.isRecoverable()).isFalse();
        assertThat(code.getDefaultMessage()).isEqualTo("이미 시작되었거나 입장할 수 없는 로비입니다.");
    }

    @Test
    @DisplayName("강퇴된 유저 재입장은 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyKickedUser_contract() {
        // given
        StompErrorCode code = StompErrorCode.LOBBY_KICKED_USER;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RETURN_TO_LOBBY_LIST);
        assertThat(code.isRecoverable()).isFalse();
        assertThat(code.getDefaultMessage()).isEqualTo("강퇴된 로비에는 재입장할 수 없습니다.");
    }

    @Test
    @DisplayName("stale 세션은 재연결 대상이며 복구 가능한 에러다")
    void lobbyStaleSession_contract() {
        // given
        StompErrorCode code = StompErrorCode.LOBBY_STALE_SESSION;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RECONNECT);
        assertThat(code.isRecoverable()).isTrue();
        assertThat(code.getDefaultMessage()).isEqualTo("더 최신 WebSocket 세션이 이미 존재합니다. 다시 접속해주세요.");
    }

    @Test
    @DisplayName("Redis 또는 Lua 일시 장애는 새로고침 후 재시도 대상이다")
    void lobbyEnterTemporarilyUnavailable_contract() {
        // given
        StompErrorCode code = StompErrorCode.LOBBY_ENTER_TEMPORARILY_UNAVAILABLE;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.REFRESH_AND_RETRY);
        assertThat(code.isRecoverable()).isTrue();
        assertThat(code.getDefaultMessage()).isEqualTo("일시적으로 로비 입장 상태를 확인할 수 없습니다. 새로고침 후 다시 시도해주세요.");
    }

    @Test
    @DisplayName("CONNECT 사용자 식별자 누락은 WebSocket 연결 재시도 대상이다")
    void connectUserIdentifierMissing_contract() {
        // given
        StompErrorCode code = StompErrorCode.CONNECT_USER_IDENTIFIER_MISSING;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RETRY_CONNECT);
        assertThat(code.isRecoverable()).isTrue();
        assertThat(code.getDefaultMessage()).isEqualTo("사용자 식별자가 없습니다. 다시 로그인 후 접속해주세요.");
    }

    @Test
    @DisplayName("revoke된 세션의 CONNECT는 재로그인 후 재접속 대상이며 복구 가능한 에러다")
    void connectSessionRevoked_contract() {
        // given
        StompErrorCode code = StompErrorCode.CONNECT_SESSION_REVOKED;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.RETRY_CONNECT);
        assertThat(code.isRecoverable()).isTrue();
        assertThat(code.getDefaultMessage())
                .isEqualTo("세션이 만료되었거나 다른 기기에서 로그인되었습니다. 다시 로그인 후 접속해주세요.");
    }

    @Test
    @DisplayName("서버 내부 STOMP 오류는 새로고침 후 재시도 대상이다")
    void internalStompError_contract() {
        // given
        StompErrorCode code = StompErrorCode.INTERNAL_STOMP_ERROR;

        // then
        assertThat(code.getAction()).isEqualTo(StompErrorAction.REFRESH_AND_RETRY);
        assertThat(code.isRecoverable()).isTrue();
        assertThat(code.getDefaultMessage()).isEqualTo("WebSocket 처리 중 서버 오류가 발생했습니다.");
    }
}