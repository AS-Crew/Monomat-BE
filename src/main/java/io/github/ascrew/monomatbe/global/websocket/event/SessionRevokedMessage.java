package io.github.ascrew.monomatbe.global.websocket.event;

/**
 * revoke된 세션에게 /user/queue/auth로 전송하는 세션 종료 알림 페이로드. (#204)
 *
 * [설계 의도]
 * FE는 message 문자열이 아니라 code/action을 기준으로 분기한다.
 * STOMP ERROR 프레임과 동일하게 action을 내려, FE가 RELOGIN(인증 정보 폐기 후 로그인 화면 이동)
 * 정책을 일관되게 처리할 수 있도록 한다.
 *
 * @param code    클라이언트 분기용 코드 (예: "SESSION_REVOKED")
 * @param message 사용자 안내 메시지
 * @param action  후속 동작 (예: "RELOGIN")
 */
public record SessionRevokedMessage(
        String code,
        String message,
        String action
) {}
