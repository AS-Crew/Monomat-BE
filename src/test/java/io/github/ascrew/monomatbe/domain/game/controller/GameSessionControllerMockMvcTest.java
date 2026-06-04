package io.github.ascrew.monomatbe.domain.game.controller;

import io.github.ascrew.monomatbe.domain.game.dto.CurrentRoundStatusResponse;
import io.github.ascrew.monomatbe.domain.game.service.GameSessionQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.github.ascrew.monomatbe.global.security.jwt.JwtAuthenticationFilter;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameSessionController.class)
@AutoConfigureMockMvc(addFilters = false)
class GameSessionControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionQueryService gameSessionQueryService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @TestConfiguration
    static class Config implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/game/{code}/round/current - 인증 정보가 없을 경우 401 Unauthorized를 반환한다")
    void getCurrentRoundStatus_unauthorized() throws Exception {
        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/api/game/ABC123/round/current"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/game/{code}/round/current - 인증 정보가 유효할 경우 200 OK와 라운드 상태 정보를 반환한다")
    void getCurrentRoundStatus_success() throws Exception {
        // given
        String code = "ABC123";
        String userIdentifier = "test-user-identifier";
        CustomPrincipal principal = new CustomPrincipal(1L, userIdentifier, UserType.REGISTERED);

        CurrentRoundStatusResponse response = new CurrentRoundStatusResponse(
                1,
                "PLAYING",
                "PLAYBACK",
                30,
                System.currentTimeMillis(),
                "vid123",
                "https://youtube.com/watch?v=vid123",
                0,
                30,
                15,
                false
        );

        when(gameSessionQueryService.getCurrentRoundStatus(eq(code), eq(userIdentifier)))
                .thenReturn(response);

        java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken authentication =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, authorities);

        // SecurityContextHolder에 직접 인증 객체 설정 (addFilters = false 대응)
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // when & then
        mockMvc.perform(get("/api/game/" + code + "/round/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundNo").value(1))
                .andExpect(jsonPath("$.status").value("PLAYING"))
                .andExpect(jsonPath("$.roundPhase").value("PLAYBACK"))
                .andExpect(jsonPath("$.videoId").value("vid123"))
                .andExpect(jsonPath("$.youtubeUrl").value("https://youtube.com/watch?v=vid123"))
                .andExpect(jsonPath("$.remainingSeconds").value(15))
                .andExpect(jsonPath("$.isCorrect").value(false));
    }
}
