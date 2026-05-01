package io.github.ascrew.monomatbe.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI monomatOpenAPI() {
        return new OpenAPI()
                .components(new Components())
                .info(new Info()
                        .title("Monomat API")
                        .description("실시간 YouTube 음악 퀴즈 게임 Monomat 백엔드 API 명세")
                        .version("v0.0.1"));
    }
}