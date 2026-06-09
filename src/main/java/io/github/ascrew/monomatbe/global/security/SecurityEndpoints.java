package io.github.ascrew.monomatbe.global.security;

/**
 * Security 설정에서 사용하는 엔드포인트 경로를 중앙에서 관리하는 상수 클래스
 */

public final class SecurityEndpoints {

    private SecurityEndpoints() {}

    // 인증 없이 허용하는 경로 (permitAll)

    // WebSocket 엔드포인트
    public static final String WS = "/ws/**";

    // 게스트 로그인
    public static final String AUTH_GUEST = "/api/auth/guest";

    // 회원가입
    public static final String AUTH_REGISTER = "/api/auth/register";

    // 로그인
    public static final String AUTH_LOGIN = "/api/auth/login";

    // 토큰 재발급
    public static final String AUTH_REFRESH = "/api/auth/refresh";


    // Swagger (dev 허용 / prod 차단)

    public static final String SWAGGER_UI = "/swagger-ui/**";
    public static final String SWAGGER_HTML = "/swagger-ui.html";
    public static final String API_DOCS = "/v3/api-docs";
    public static final String API_DOCS_ALL = "/v3/api-docs/**";


    // 편의 메서드 - permitAll 경로 배열

    /**
     * 인증 없이 허용할 공통 경로 배열을 반환한다.
     * SecurityConfigDev / SecurityConfigProd 양쪽에서 재사용한다.
     */
    public static String[] publicEndpoints() {
        return new String[] {
                WS, AUTH_GUEST, AUTH_REGISTER, AUTH_LOGIN, AUTH_REFRESH
        };
    }

    /**
     * Swagger 관련 경로 배열을 반환한다.
     * dev 환경에서는 permitAll, prod 환경에서는 denyAll에 사용한다..
     */
    public static String[] swaggerEndpoints() {
        return new String[] {
                SWAGGER_UI, SWAGGER_HTML, API_DOCS, API_DOCS_ALL
        };
    }
}
