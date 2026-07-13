package io.github.ascrew.monomatbe.global.websocket.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP 오류 코드의 FE 계약을 검증한다.
 *
 * FE는 사용자 메시지가 아니라 action과 recoverable을 기준으로
 * 화면 이동, 토큰 갱신, 재연결 및 재로그인 정책을 결정한다.
 *
 * 따라서 StompErrorCode의 enum 값과 계약 속성은
 * 임의로 변경하지 않고 테스트로 고정한다.
 */
class StompErrorCodeContractTest {

    @Test
    @DisplayName("로비 없음은 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyNotFound_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.LOBBY_NOT_FOUND;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RETURN_TO_LOBBY_LIST
                );

        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "존재하지 않는 로비입니다."
                );
    }

    @Test
    @DisplayName("로비 만원은 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyFull_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.LOBBY_FULL;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RETURN_TO_LOBBY_LIST
                );

        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "로비 최대 인원에 도달했습니다."
                );
    }

    @Test
    @DisplayName("이미 시작된 로비는 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyNotWaiting_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.LOBBY_NOT_WAITING;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RETURN_TO_LOBBY_LIST
                );

        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "이미 시작되었거나 입장할 수 없는 로비입니다."
                );
    }

    @Test
    @DisplayName("강퇴된 유저 재입장은 로비 목록 복귀 대상이며 복구 불가능 에러다")
    void lobbyKickedUser_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.LOBBY_KICKED_USER;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RETURN_TO_LOBBY_LIST
                );

        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "강퇴된 로비에는 재입장할 수 없습니다."
                );
    }

    @Test
    @DisplayName("stale 세션은 재연결 대상이며 복구 가능한 에러다")
    void lobbyStaleSession_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.LOBBY_STALE_SESSION;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RECONNECT
                );

        assertThat(code.isRecoverable())
                .isTrue();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "더 최신 WebSocket 세션이 이미 존재합니다. 다시 접속해주세요."
                );
    }

    @Test
    @DisplayName("Redis 또는 Lua 일시 장애는 새로고침 후 재시도 대상이다")
    void lobbyEnterTemporarilyUnavailable_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.LOBBY_ENTER_TEMPORARILY_UNAVAILABLE;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.REFRESH_AND_RETRY
                );

        assertThat(code.isRecoverable())
                .isTrue();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "일시적으로 로비 입장 상태를 확인할 수 없습니다. 새로고침 후 다시 시도해주세요."
                );
    }

    /**
     * #211 이전 연결 계약을 사용하는 오류 코드이다.
     *
     * 신규 STOMP CONNECT에서는 userIdentifier native header가 아니라
     * Access Token을 인증 기준으로 사용하지만 기존 FE 계약 호환을 위해
     * enum 자체는 유지한다.
     */
    @Test
    @DisplayName("CONNECT 사용자 식별자 누락은 WebSocket 연결 재시도 대상이다")
    void connectUserIdentifierMissing_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.CONNECT_USER_IDENTIFIER_MISSING;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RETRY_CONNECT
                );

        assertThat(code.isRecoverable())
                .isTrue();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "사용자 식별자가 없습니다. 다시 로그인 후 접속해주세요."
                );
    }

    @Test
    @DisplayName("revoke된 세션의 CONNECT는 재로그인 대상이며 현재 세션으로 복구할 수 없다")
    void connectSessionRevoked_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.CONNECT_SESSION_REVOKED;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RELOGIN
                );

        /*
         * Redis 활성 세션이 이미 폐기된 상태이므로
         * 같은 Access Token이나 동일한 인증 상태로 다시 연결해도
         * CONNECT 단계에서 다시 거부된다.
         */
        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "세션이 만료되었거나 다른 기기에서 로그인되었습니다. 다시 로그인 후 접속해주세요."
                );
    }

    @Test
    @DisplayName("연결 이후 revoke된 세션은 재로그인 대상이며 현재 세션으로 복구할 수 없다")
    void sessionRevoked_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.SESSION_REVOKED;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RELOGIN
                );

        /*
         * 강제 로그인이나 로그아웃으로 활성 세션 마커가 삭제된 상태이다.
         *
         * FE가 동일한 인증 정보로 자동 재연결하더라도
         * Redis 활성 세션 검증을 다시 통과할 수 없으므로
         * 사용자가 다시 로그인해야 한다.
         */
        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "세션이 만료되었거나 다른 기기에서 로그인되었습니다. 다시 로그인 후 접속해주세요."
                );
    }

    @Test
    @DisplayName("Access Token 누락은 토큰 갱신 후 재연결 대상이다")
    void accessTokenMissing_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.ACCESS_TOKEN_MISSING;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.REFRESH_TOKEN
                );

        /*
         * Access Token이 저장소 복구 또는 Refresh Token을 통해
         * 다시 발급될 수 있으므로 자동 복구 가능한 오류로 분류한다.
         */
        assertThat(code.isRecoverable())
                .isTrue();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "인증 토큰이 없습니다. 토큰을 갱신한 후 다시 연결해주세요."
                );
    }

    @Test
    @DisplayName("만료된 Access Token은 토큰 갱신 후 재연결 대상이다")
    void accessTokenExpired_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.ACCESS_TOKEN_EXPIRED;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.REFRESH_TOKEN
                );

        /*
         * Refresh Token이 유효하다면 새로운 Access Token을 발급받아
         * STOMP CONNECT를 다시 수행할 수 있다.
         */
        assertThat(code.isRecoverable())
                .isTrue();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "인증 토큰이 만료되었습니다. 토큰을 갱신한 후 다시 연결해주세요."
                );
    }

    @Test
    @DisplayName("유효하지 않은 Access Token은 재로그인 대상이며 자동 복구할 수 없다")
    void accessTokenInvalid_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.ACCESS_TOKEN_INVALID;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.RELOGIN
                );

        /*
         * 위변조, 잘못된 토큰 유형 또는 필수 Claim 누락 가능성이 있으므로
         * 기존 인증 정보를 신뢰하지 않고 재로그인을 요구한다.
         */
        assertThat(code.isRecoverable())
                .isFalse();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "유효하지 않은 인증 토큰입니다. 다시 로그인해주세요."
                );
    }

    @Test
    @DisplayName("서버 내부 STOMP 오류는 새로고침 후 재시도 대상이다")
    void internalStompError_contract() {
        // given
        StompErrorCode code =
                StompErrorCode.INTERNAL_STOMP_ERROR;

        // then
        assertThat(code.getAction())
                .isEqualTo(
                        StompErrorAction.REFRESH_AND_RETRY
                );

        assertThat(code.isRecoverable())
                .isTrue();

        assertThat(code.getDefaultMessage())
                .isEqualTo(
                        "WebSocket 처리 중 서버 오류가 발생했습니다."
                );
    }
}