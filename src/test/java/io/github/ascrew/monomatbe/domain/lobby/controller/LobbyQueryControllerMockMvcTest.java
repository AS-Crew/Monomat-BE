package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyListItemResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPageResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbySearchCondition;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/lobbies 응답의 실제 JSON 직렬화 계약을 고정하는 MockMvc 테스트.
 *
 * [목적]
 * 응답 타입이 LobbyPageResponse&lt;LobbyRedisDto&gt; -&gt; LobbyPageResponse&lt;LobbyListItemResponse&gt;로
 * 바뀌고 hostNickname이 추가되었다. 이 변경이 기존 필드명/직렬화(특히 isPrivate)와 페이지 메타를
 * 깨지 않는지 실제 JSON 기준으로 회귀 방지한다.
 *
 * [보안 필터]
 * 직렬화 계약 검증이 목적이므로 addFilters = false로 보안 필터 체인을 우회한다.
 * (/api/lobbies는 prod에서 인증이 필요한 엔드포인트다)
 */
@WebMvcTest(LobbyQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
class LobbyQueryControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LobbyQueryService lobbyQueryService;

    /*
     * JwtAuthenticationFilter는 @Component Filter라 @WebMvcTest 슬라이스에 포함되지만
     * StringRedisTemplate 등 인프라 빈을 요구한다. addFilters = false로 필터는 적용하지 않으므로,
     * 컨텍스트 로드를 위해 mock으로 대체한다.
     */
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("GET /api/lobbies 응답 item에 hostNickname이 포함되고 기존 필드/페이지 메타가 동일하게 직렬화된다")
    void getPublicLobbies_serializesHostNicknameAndLegacyFields() throws Exception {
        // given
        LobbyListItemResponse item = new LobbyListItemResponse(
                "ABC123",
                "모노유저",
                "K-POP 퀴즈방",
                10L,
                "아이돌 명곡",
                "K-POP",
                8,
                3,
                false,
                "WAITING",
                15,
                30,
                1710000000000L
        );
        LobbyPageResponse<LobbyListItemResponse> response =
                new LobbyPageResponse<>(List.of(item), 0, 6, true);

        when(lobbyQueryService.getPublicLobbyPage(any(LobbySearchCondition.class)))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/lobbies"))
                .andExpect(status().isOk())
                // 신규 필드
                .andExpect(jsonPath("$.items[0].hostNickname").value("모노유저"))
                // 기존 필드 회귀 가드 (필드명/값 동일)
                .andExpect(jsonPath("$.items[0].code").value("ABC123"))
                // hostId(userIdentifier)는 공개 목록 응답에서 노출되지 않아야 한다
                .andExpect(jsonPath("$.items[0].hostId").doesNotExist())
                .andExpect(jsonPath("$.items[0].title").value("K-POP 퀴즈방"))
                .andExpect(jsonPath("$.items[0].mapId").value(10))
                .andExpect(jsonPath("$.items[0].mapTitle").value("아이돌 명곡"))
                .andExpect(jsonPath("$.items[0].mapCategory").value("K-POP"))
                .andExpect(jsonPath("$.items[0].maxPlayers").value(8))
                .andExpect(jsonPath("$.items[0].currentPlayers").value(3))
                // Boolean 필드명이 "isPrivate"로 직렬화되는지 고정
                .andExpect(jsonPath("$.items[0].isPrivate").value(false))
                .andExpect(jsonPath("$.items[0].status").value("WAITING"))
                .andExpect(jsonPath("$.items[0].questionCount").value(15))
                .andExpect(jsonPath("$.items[0].timeLimitSeconds").value(30))
                .andExpect(jsonPath("$.items[0].createdAtEpochMillis").value(1710000000000L))
                // 페이지 메타
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(6))
                .andExpect(jsonPath("$.hasNext").value(true));
    }
}
