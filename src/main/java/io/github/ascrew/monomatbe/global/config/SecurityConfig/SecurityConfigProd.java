package io.github.ascrew.monomatbe.global.config.SecurityConfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Configuration
@EnableWebSecurity
@Profile("prod")
public class SecurityConfigProd {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)      // CSRF 보호 비활성화
            .formLogin(AbstractHttpConfigurer::disable) // 폼 로그인 비활성화
            .httpBasic(AbstractHttpConfigurer::disable) // HTTP Basic 인증 비활성화
            .authorizeHttpRequests(auth -> auth
                    // 운영 환경이므로 Swagger 관련 URL은 접근 차단
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").denyAll()
                    
                    // 인증 없이 접근해야 하는 웹소켓 및 Auth URL 허용
                    .requestMatchers("/ws/**").permitAll()
                    .requestMatchers("/api/auth/guest", "/api/auth/register", "/api/auth/login").permitAll()
                    
                    // 그 외 모든 요청에 대해 인증 필요 (운영 환경에서는 인증된 사용자만 접근 허용)
                    .anyRequest().authenticated()
            ); 

        return http.build();
    }
}