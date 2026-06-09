package io.github.ascrew.monomatbe.global.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * CORS 허용 출처 설정 (단일 소스).
 *
 * 프론트엔드(메인 도메인)와 백엔드(api 서브도메인)가 서로 다른 출처이므로
 * 허용 출처를 monomat.cors.allowed-origins(=${CORS_ALLOWED_ORIGINS})로 외부화한다.
 * REST CORS({@code CorsConfig})와 WebSocket({@code WebSocketConfig})이 모두 이 객체 하나를 참조한다.
 *
 * prod 프로파일은 application-prod.properties에서 기본값 없이 ${CORS_ALLOWED_ORIGINS}를 주입하므로,
 * 값이 비어 있으면 @NotEmpty 검증으로 기동 시점에 실패(fail-fast)한다.
 *
 * 추가로 기동 시점에 각 값을 정확한 Origin(scheme + host, path/와일드카드 없음)으로 강하게 검증한다.
 * {@code *}나 {@code https://*.monomat.games} 같은 와일드카드는 거부하여 REST({@link CorsConfig},
 * 정확 매칭)와 WebSocket({@link WebSocketConfig}, 패턴 매칭)의 동작 불일치를 막는다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "monomat.cors")
public class CorsProperties {

    /**
     * 허용할 프론트엔드 출처 목록. 콤마로 구분된 값이 바인딩된다.
     * 예: https://monomat.games,https://www.monomat.games
     */
    @NotEmpty
    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 기동 시점에 허용 출처를 정규화하고 형식을 검증한다.
     * 양끝 공백을 제거하고 빈 원소(후행 콤마 등)를 걸러낸 뒤, 각 값이 정확한 Origin인지 확인한다.
     */
    @PostConstruct
    void validateAllowedOrigins() {
        allowedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();

        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException("monomat.cors.allowed-origins must not be empty");
        }

        allowedOrigins.forEach(CorsProperties::validateOrigin);
    }

    private static void validateOrigin(String origin) {
        // *, https://*.monomat.games 등 모든 와일드카드를 거부한다.
        // WebSocket의 setAllowedOriginPatterns가 패턴으로 해석해 의도보다 넓게 허용하는 것을 막는다.
        if (origin.contains("*")) {
            throw new IllegalStateException("Wildcard origin is not allowed: " + origin);
        }

        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid origin: " + origin, e);
        }

        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalStateException("Origin must include scheme and host: " + origin);
        }

        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalStateException("Origin scheme must be http or https: " + origin);
        }

        // 브라우저 Origin 헤더에는 path/query/fragment/userinfo가 없다.
        // 후행 슬래시("/")까지 거부해 setAllowedOrigins에서 조용히 매칭되지 않는 오설정을 막는다.
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            throw new IllegalStateException("Origin must not include path: " + origin);
        }

        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalStateException("Origin must not include query, fragment, or user info: " + origin);
        }
    }
}
