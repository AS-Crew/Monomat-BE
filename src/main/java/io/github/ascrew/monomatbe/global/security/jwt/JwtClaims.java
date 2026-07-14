package io.github.ascrew.monomatbe.global.security.jwt;

/**
 * JWT 클레임 키를 중앙에서 관리하는 상수 클래스
 *
 * [설계 이유]
 * - JwtTokenProvider (발급)와 JwtAuthenticationFilter (검증)가 동일한 클레임 키를 사용해야 한다.
 * - 문자열 리터럴이 각자 하드코딩하면 오타 시 런타임에서야 발견되므로 상수로 통일하여 컴파일 타임에 감지되도록 한다.
 */
public final class JwtClaims {

    private JwtClaims() {
    }

    /**
     * JWT 사용 목적을 구분하는 Claim 키
     * 저장 값 : ACCESS, REFRESH
     */
    public static final String TOKEN_TYPE = "tokenType";

    // 게스트/회원 공통 UUID 식별자 클레임 키
    public static final String USER_IDENTIFIER = "userIdentifier";

    // 사용자 유형 클레임 키 (GUEST | REGISTERED)
    public static final String USER_TYPE = "userType";

    // 사용자 권한 클레임 키 (USER | ADMIN)
    public static final String USER_ROLE = "userRole";

    // Refresh Token의 세션 ID 클레임 키
    public static final String SESSION_ID = "sessionId";

    // Spring Security 권한 접두사
    public static final String ROLE_PREFIX = "ROLE_";
}