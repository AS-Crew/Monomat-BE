package io.github.ascrew.monomatbe.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    // =========================================================
    // 상수 추가
    // =========================================================
    private static final String API_TITLE = "Monomat API";
    private static final String API_DESCRIPTION = "실시간 YouTube 음악 퀴즈 게임 Monomat 백엔드 API 명세";
    private static final String API_VERSION = "v0.0.1";

    @Bean
    public OpenAPI monomatOpenAPI() {
        return new OpenAPI()
                .components(new Components())
                .info(new Info()
                        .title(API_TITLE)
                        .description(API_DESCRIPTION)
                        .version(API_VERSION));
    }
}