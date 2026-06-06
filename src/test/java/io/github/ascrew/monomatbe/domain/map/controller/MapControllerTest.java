package io.github.ascrew.monomatbe.domain.map.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.service.MapService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MapControllerTest {

    /*
     * Security Filter Chain을 로딩하지 않는 standalone controller test
     * 이 테스트는 신규 경로가 공개 맵 조회가 아니라 소유자 전용 조회 메서드로 매핑되는지,
     * AuthenticationPrincipalArgumentResolver가 CustomPrincipal을 서비스로 전달하는지 검증한다.
     *
     * JWT 인증 실패(401)와 @PreAuthorize 동작은 Spring Security 통합 테스트에서 검증해야 한다.
     */

    private MockMvc mockMvc;

    @Mock
    private MapService mapService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MapController(mapService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getMyMap_withAuthenticatedOwner_returns200() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        MapDetailResponse response = MapDetailResponse.builder()
                .id(100L)
                .ownerId(10L)
                .ownerNickname("owner")
                .title("내 비공개 맵")
                .description("관리 페이지에서 조회할 맵")
                .category(MapCategory.JPOP)
                .numOfSong(10)
                .totalPlayTime(300)
                .isPublic(false)
                .pendingPublic(false)
                .playCount(5L)
                .createdAt(LocalDateTime.of(2026, 6, 5, 18, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 5, 18, 30))
                .build();

        when(mapService.getMyMap(100L, principal)).thenReturn(response);

        mockMvc.perform(get("/api/maps/me/{mapId}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.ownerId").value(10L))
                .andExpect(jsonPath("$.ownerNickname").value("owner"))
                .andExpect(jsonPath("$.title").value("내 비공개 맵"))
                .andExpect(jsonPath("$.description").value("관리 페이지에서 조회할 맵"))
                .andExpect(jsonPath("$.category").value("J-POP"))
                .andExpect(jsonPath("$.numOfSong").value(10))
                .andExpect(jsonPath("$.totalPlayTime").value(300))
                .andExpect(jsonPath("$.isPublic").value(false))
                .andExpect(jsonPath("$.pendingPublic").value(false))
                .andExpect(jsonPath("$.playCount").value(5));

        verify(mapService).getMyMap(100L, principal);
        verify(mapService, never()).getPublicMap(anyLong());
    }

    @Test
    void getMyMap_notOwner_returns403() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(11L, "u-11", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        when(mapService.getMyMap(100L, principal))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "본인 소유의 맵만 조회할 수 있습니다."
                ));

        mockMvc.perform(get("/api/maps/me/{mapId}", 100L))
                .andExpect(status().isForbidden());

        verify(mapService).getMyMap(100L, principal);
        verify(mapService, never()).getPublicMap(anyLong());
    }

    private UsernamePasswordAuthenticationToken authenticationToken(CustomPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER")
        );
    }
}