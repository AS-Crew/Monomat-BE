package io.github.ascrew.monomatbe.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * REST API CORS 설정.
 * 허용 출처는 {@link CorsProperties}(monomat.cors.allowed-origins) 단일 소스를 참조한다.
 * 빈 이름이 corsConfigurationSource이면 SecurityFilterChain의 http.cors()가 자동으로 사용한다.
 */
@Configuration
public class CorsConfig {

    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // JWT는 Authorization 헤더로 전달하고 쿠키를 사용하지 않으므로 필요한 헤더만 허용한다.
        // 추후 X-Request-Id 등 커스텀 헤더가 필요하면 명시적으로 추가한다.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // SockJS(sockjs-client)는 /ws/info 등 모든 XHR 요청을 withCredentials=true(자격증명 모드)로 보낸다.
        // 이때 브라우저는 응답에 Access-Control-Allow-Credentials: true 가 있어야만 응답을 허용하며,
        // 이 값이 false면 응답이 200이어도 CORS로 차단되어 WebSocket 채팅 연결이 실패한다.
        // 허용 출처는 정확한 Origin 목록(CorsProperties가 와일드카드/*를 기동 시 거부)이라 credentials=true 와 함께 써도 안전하다.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
