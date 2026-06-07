package io.github.ascrew.monomatbe.global.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

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
}
