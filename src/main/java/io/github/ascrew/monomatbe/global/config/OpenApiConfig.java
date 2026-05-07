package io.github.ascrew.monomatbe.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String API_TITLE = "Monomat API";
    private static final String API_DESCRIPTION = "실시간 YouTube 음악 퀴즈 게임 Monomat 백엔드 API 명세";
    private static final String API_VERSION = "v0.0.1";

    /**
     * Swagger UI의 Authorize 버튼에 표시될 보안 스킴 이름.
     * addSecuritySchemes()와 addSecurityItem()에서 동일한 이름을 사용해야 합니다.
     */
    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI monomatOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        // Bearer 인증 스킴 등록 → Swagger UI 우측 상단에 Authorize 버튼 활성화
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("게스트 로그인 후 발급받은 accessToken을 입력하세요.")))
                // 전체 API에 Bearer 인증을 기본 적용
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .info(new Info()
                        .title(API_TITLE)
                        .description(API_DESCRIPTION)
                        .version(API_VERSION));
    }
}