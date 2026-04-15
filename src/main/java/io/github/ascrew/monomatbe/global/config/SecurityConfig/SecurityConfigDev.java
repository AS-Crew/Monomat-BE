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
@Profile("dev")
public class SecurityConfigDev {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)      // CSRF 보호 비활성화
            .formLogin(AbstractHttpConfigurer::disable) // 폼 로그인 비활성화
            .httpBasic(AbstractHttpConfigurer::disable) // HTTP Basic 인증 비활성화
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/ws/**").permitAll()
                    .anyRequest()
                    .permitAll()
            ); // 모든 요청 허용 (개발 환경에서는 인증 없이 접근 허용)

        return http.build();
    }
}
