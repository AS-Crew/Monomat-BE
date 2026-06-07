package io.github.ascrew.monomatbe.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * REST API CORS 설정.
 * 프론트엔드(메인 도메인)와 백엔드(api 서브도메인)가 서로 다른 출처이므로
 * 허용 출처를 monomat.cors.allowed-origins(=${CORS_ALLOWED_ORIGINS})로 외부화한다.
 * 빈 이름이 corsConfigurationSource이면 SecurityFilterChain의 http.cors()가 자동으로 사용한다.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${monomat.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // JWT는 Authorization 헤더로 전달하며 쿠키를 사용하지 않으므로 credentials는 비활성화한다.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
