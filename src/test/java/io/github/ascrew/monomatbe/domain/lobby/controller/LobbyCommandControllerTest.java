package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbySettingsRequest;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyCreateService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyJoinService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyMapUpdateService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyReadyService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbySettingsUpdateService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyStartService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LobbyCommandControllerTest {

    /*
     * Security Filter Chain을 로딩하지 않는 standalone controller test.
     * JWT 인증 실패(401)와 @PreAuthorize 동작은 Spring Security 통합 테스트에서 검증해야 한다.
     */

    private MockMvc mockMvc;

    @Mock
    private LobbyCreateService lobbyCreateService;

    @Mock
    private LobbyJoinService lobbyJoinService;

    @Mock
    private LobbyReadyService lobbyReadyService;

    @Mock
    private LobbyStartService lobbyStartService;

    @Mock
    private LobbyMapUpdateService lobbyMapUpdateService;

    @Mock
    private LobbySettingsUpdateService lobbySettingsUpdateService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LobbyCommandController(
                        lobbyCreateService,
                        lobbyJoinService,
                        lobbyReadyService,
                        lobbyStartService,
                        lobbyMapUpdateService,
                        lobbySettingsUpdateService
                ))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateLobbySettings_withValidRequest_returns204() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(
                10L,
                "host-user-identifier",
                UserType.REGISTERED
        );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        mockMvc.perform(patch("/api/lobbies/{code}/settings", "ABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxPlayers": 4,
                                  "questionCount": 10,
                                  "timeLimitSeconds": 30
                                }
                                """))
                .andExpect(status().isNoContent());

        ArgumentCaptor<UpdateLobbySettingsRequest> requestCaptor =
                ArgumentCaptor.forClass(UpdateLobbySettingsRequest.class);

        verify(lobbySettingsUpdateService).updateSettings(
                eq("ABC123"),
                requestCaptor.capture(),
                eq(principal)
        );

        UpdateLobbySettingsRequest capturedRequest = requestCaptor.getValue();

        assertThat(capturedRequest.maxPlayers()).isEqualTo(4);
        assertThat(capturedRequest.questionCount()).isEqualTo(10);
        assertThat(capturedRequest.timeLimitSeconds()).isEqualTo(30);
    }

    @Test
    void updateLobbySettings_invalidMaxPlayers_returns400() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(
                10L,
                "host-user-identifier",
                UserType.REGISTERED
        );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        mockMvc.perform(patch("/api/lobbies/{code}/settings", "ABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxPlayers": 1,
                                  "questionCount": 10,
                                  "timeLimitSeconds": 30
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(lobbySettingsUpdateService, never()).updateSettings(
                any(),
                any(),
                any()
        );
    }

    @Test
    void updateLobbySettings_invalidQuestionCount_returns400() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(
                10L,
                "host-user-identifier",
                UserType.REGISTERED
        );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        mockMvc.perform(patch("/api/lobbies/{code}/settings", "ABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxPlayers": 4,
                                  "questionCount": 51,
                                  "timeLimitSeconds": 30
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(lobbySettingsUpdateService, never()).updateSettings(
                any(),
                any(),
                any()
        );
    }

    @Test
    void updateLobbySettings_invalidTimeLimitSeconds_returns400() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(
                10L,
                "host-user-identifier",
                UserType.REGISTERED
        );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        mockMvc.perform(patch("/api/lobbies/{code}/settings", "ABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxPlayers": 4,
                                  "questionCount": 10,
                                  "timeLimitSeconds": 9
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(lobbySettingsUpdateService, never()).updateSettings(
                any(),
                any(),
                any()
        );
    }

    @Test
    void updateLobbySettings_serviceForbidden_returns403() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(
                20L,
                "participant-user-identifier",
                UserType.REGISTERED
        );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "방장만 로비 설정을 변경할 수 있습니다."
        )).when(lobbySettingsUpdateService).updateSettings(
                eq("ABC123"),
                any(UpdateLobbySettingsRequest.class),
                eq(principal)
        );

        mockMvc.perform(patch("/api/lobbies/{code}/settings", "ABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxPlayers": 4,
                                  "questionCount": 10,
                                  "timeLimitSeconds": 30
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(lobbySettingsUpdateService).updateSettings(
                eq("ABC123"),
                any(UpdateLobbySettingsRequest.class),
                eq(principal)
        );
    }

    @Test
    void updateLobbySettings_hasAuthenticatedPreAuthorizeGuard() throws NoSuchMethodException {
        Method method = LobbyCommandController.class.getDeclaredMethod(
                "updateLobbySettings",
                String.class,
                UpdateLobbySettingsRequest.class,
                CustomPrincipal.class
        );

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("isAuthenticated()");
    }

    private UsernamePasswordAuthenticationToken authenticationToken(CustomPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER")
        );
    }
}