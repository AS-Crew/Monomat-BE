package io.github.ascrew.monomatbe.domain.map.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsResponse;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapItemResponse;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.service.MapManageService;
import io.github.ascrew.monomatbe.domain.map.service.MapService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MapControllerTest {

    /*
     * Security Filter Chain을 로딩하지 않는 standalone controller test.
     * JWT 인증 실패(401)와 @PreAuthorize 동작은 Spring Security 통합 테스트에서 검증해야 한다.
     */

    private MockMvc mockMvc;

    @Mock
    private MapService mapService;

    @Mock
    private MapManageService mapManageService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MapController(mapService, mapManageService))
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

    @Test
    void createMapWithItems_withValidRequest_returns201() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        MapDetailResponse mapResponse = MapDetailResponse.builder()
                .id(1L)
                .ownerId(10L)
                .ownerNickname("owner")
                .title("J-POP 퀴즈")
                .description("J-POP 중심 퀴즈 맵")
                .category(MapCategory.JPOP)
                .numOfSong(2)
                .totalPlayTime(60)
                .isPublic(false)
                .pendingPublic(false)
                .playCount(0L)
                .createdAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .build();

        MapItemResponse firstItemResponse = MapItemResponse.builder()
                .id(10L)
                .mapId(1L)
                .orderNum(1)
                .youtubeUrl("https://www.youtube.com/watch?v=video1")
                .videoId("video1")
                .startTime(30)
                .endTime(60)
                .title("YouTube title 1")
                .artist("YouTube author 1")
                .thumbnailUrl("https://thumbnail/1")
                .answers(List.of("ditto"))
                .hint("ㄷㅌ")
                .hintTime(15)
                .createdAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .build();

        MapItemResponse secondItemResponse = MapItemResponse.builder()
                .id(11L)
                .mapId(1L)
                .orderNum(2)
                .youtubeUrl("https://www.youtube.com/watch?v=video2")
                .videoId("video2")
                .startTime(0)
                .endTime(30)
                .title("YouTube title 2")
                .artist("YouTube author 2")
                .thumbnailUrl("https://thumbnail/2")
                .answers(List.of("omg"))
                .hint("ㅇㅇㅈ")
                .hintTime(15)
                .createdAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 7, 12, 0))
                .build();

        CreateMapWithItemsResponse response = CreateMapWithItemsResponse.builder()
                .map(mapResponse)
                .items(List.of(firstItemResponse, secondItemResponse))
                .build();

        when(mapManageService.createMapWithItems(any(), eq(principal))).thenReturn(response);

        mockMvc.perform(post("/api/maps/with-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "J-POP 퀴즈",
                              "description": "J-POP 중심 퀴즈 맵",
                              "category": "J-POP",
                              "isPublic": false,
                              "items": [
                                {
                                  "orderNum": 1,
                                  "youtubeUrl": "https://www.youtube.com/watch?v=video1",
                                  "startTime": 30,
                                  "endTime": 60,
                                  "answers": ["ditto"],
                                  "hint": "ㄷㅌ",
                                  "hintTime": 15
                                },
                                {
                                  "orderNum": 2,
                                  "youtubeUrl": "https://www.youtube.com/watch?v=video2",
                                  "startTime": 0,
                                  "endTime": 30,
                                  "answers": ["omg"],
                                  "hint": "ㅇㅇㅈ",
                                  "hintTime": 15
                                }
                              ]
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.map.id").value(1L))
                .andExpect(jsonPath("$.map.ownerId").value(10L))
                .andExpect(jsonPath("$.map.ownerNickname").value("owner"))
                .andExpect(jsonPath("$.map.title").value("J-POP 퀴즈"))
                .andExpect(jsonPath("$.map.description").value("J-POP 중심 퀴즈 맵"))
                .andExpect(jsonPath("$.map.category").value("J-POP"))
                .andExpect(jsonPath("$.map.numOfSong").value(2))
                .andExpect(jsonPath("$.map.totalPlayTime").value(60))
                .andExpect(jsonPath("$.map.isPublic").value(false))
                .andExpect(jsonPath("$.map.pendingPublic").value(false))
                .andExpect(jsonPath("$.map.playCount").value(0))
                .andExpect(jsonPath("$.items[0].id").value(10L))
                .andExpect(jsonPath("$.items[0].mapId").value(1L))
                .andExpect(jsonPath("$.items[0].orderNum").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value("video1"))
                .andExpect(jsonPath("$.items[0].answers[0]").value("ditto"))
                .andExpect(jsonPath("$.items[0].hint").value("ㄷㅌ"))
                .andExpect(jsonPath("$.items[1].id").value(11L))
                .andExpect(jsonPath("$.items[1].mapId").value(1L))
                .andExpect(jsonPath("$.items[1].orderNum").value(2))
                .andExpect(jsonPath("$.items[1].videoId").value("video2"))
                .andExpect(jsonPath("$.items[1].answers[0]").value("omg"))
                .andExpect(jsonPath("$.items[1].hint").value("ㅇㅇㅈ"));

        verify(mapManageService).createMapWithItems(any(), eq(principal));
    }

    @Test
    void createMapWithItems_invalidRequest_returns400() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        mockMvc.perform(post("/api/maps/with-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "",
                              "description": "J-POP 중심 퀴즈 맵",
                              "category": "J-POP",
                              "isPublic": false,
                              "items": []
                            }
                            """))
                .andExpect(status().isBadRequest());

        verify(mapManageService, never()).createMapWithItems(any(), any());
    }

    @Test
    void createMapWithItems_serviceForbidden_returns403() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(20L, "u-20", UserType.GUEST);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        when(mapManageService.createMapWithItems(any(), eq(principal)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "정식 회원만 맵을 관리할 수 있습니다."
                ));

        mockMvc.perform(post("/api/maps/with-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "J-POP 퀴즈",
                              "description": "J-POP 중심 퀴즈 맵",
                              "category": "J-POP",
                              "isPublic": false,
                              "items": [
                                {
                                  "orderNum": 1,
                                  "youtubeUrl": "https://www.youtube.com/watch?v=video1",
                                  "startTime": 30,
                                  "endTime": 60,
                                  "answers": ["ditto"],
                                  "hint": "ㄷㅌ",
                                  "hintTime": 15
                                }
                              ]
                            }
                            """))
                .andExpect(status().isForbidden());

        verify(mapManageService).createMapWithItems(any(), eq(principal));
    }

    @Test
    void updateManagedMap_withValidRequest_returns200() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        MapDetailResponse mapResponse = MapDetailResponse.builder()
                .id(1L)
                .ownerId(10L)
                .ownerNickname("owner")
                .title("J-POP 퀴즈")
                .description("J-POP 중심 퀴즈 맵")
                .category(MapCategory.JPOP)
                .numOfSong(2)
                .totalPlayTime(60)
                .isPublic(false)
                .pendingPublic(false)
                .playCount(0L)
                .createdAt(LocalDateTime.of(2026, 6, 6, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 6, 12, 10))
                .build();

        MapItemResponse itemResponse = MapItemResponse.builder()
                .id(10L)
                .mapId(1L)
                .orderNum(1)
                .youtubeUrl("https://www.youtube.com/watch?v=video1")
                .videoId("video1")
                .startTime(30)
                .endTime(60)
                .title("YouTube title")
                .artist("YouTube author")
                .thumbnailUrl("https://thumbnail")
                .answers(List.of("ditto"))
                .hint("ㄷㅌ")
                .hintTime(15)
                .createdAt(LocalDateTime.of(2026, 6, 6, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 6, 12, 10))
                .build();

        ManageMapResponse response = ManageMapResponse.builder()
                .map(mapResponse)
                .items(List.of(itemResponse))
                .build();

        when(mapManageService.updateManagedMap(eq(1L), any(), eq(principal))).thenReturn(response);

        mockMvc.perform(put("/api/maps/{mapId}/manage", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "J-POP 퀴즈",
                                  "description": "J-POP 중심 퀴즈 맵",
                                  "category": "J-POP",
                                  "isPublic": false,
                                  "items": [
                                    {
                                      "id": 10,
                                      "orderNum": 1,
                                      "youtubeUrl": "https://www.youtube.com/watch?v=video1",
                                      "startTime": 30,
                                      "endTime": 60,
                                      "answers": ["ditto"],
                                      "hint": "ㄷㅌ",
                                      "hintTime": 15
                                    },
                                    {
                                      "id": null,
                                      "orderNum": 2,
                                      "youtubeUrl": "https://www.youtube.com/watch?v=video2",
                                      "startTime": 0,
                                      "endTime": 30,
                                      "answers": ["omg"],
                                      "hint": "ㅇㅇㅈ",
                                      "hintTime": 15
                                    }
                                  ],
                                  "deletedItemIds": [11, 12]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.map.id").value(1L))
                .andExpect(jsonPath("$.map.ownerId").value(10L))
                .andExpect(jsonPath("$.map.ownerNickname").value("owner"))
                .andExpect(jsonPath("$.map.title").value("J-POP 퀴즈"))
                .andExpect(jsonPath("$.map.description").value("J-POP 중심 퀴즈 맵"))
                .andExpect(jsonPath("$.map.category").value("J-POP"))
                .andExpect(jsonPath("$.map.numOfSong").value(2))
                .andExpect(jsonPath("$.map.totalPlayTime").value(60))
                .andExpect(jsonPath("$.map.isPublic").value(false))
                .andExpect(jsonPath("$.map.pendingPublic").value(false))
                .andExpect(jsonPath("$.map.playCount").value(0))
                .andExpect(jsonPath("$.items[0].id").value(10L))
                .andExpect(jsonPath("$.items[0].mapId").value(1L))
                .andExpect(jsonPath("$.items[0].orderNum").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value("video1"))
                .andExpect(jsonPath("$.items[0].answers[0]").value("ditto"))
                .andExpect(jsonPath("$.items[0].hint").value("ㄷㅌ"));

        verify(mapManageService).updateManagedMap(eq(1L), any(), eq(principal));
    }

    @Test
    void updateManagedMap_invalidRequest_returns400() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        mockMvc.perform(put("/api/maps/{mapId}/manage", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "",
                                  "description": "J-POP 중심 퀴즈 맵",
                                  "category": "J-POP",
                                  "isPublic": false,
                                  "items": [],
                                  "deletedItemIds": []
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(mapManageService, never()).updateManagedMap(anyLong(), any(), any());
    }

    @Test
    void updateManagedMap_serviceForbidden_returns403() throws Exception {
        CustomPrincipal principal = new CustomPrincipal(99L, "u-99", UserType.REGISTERED);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken(principal));

        when(mapManageService.updateManagedMap(eq(1L), any(), eq(principal)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "본인 소유의 맵만 수정할 수 있습니다."
                ));

        mockMvc.perform(put("/api/maps/{mapId}/manage", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "J-POP 퀴즈",
                                  "description": "J-POP 중심 퀴즈 맵",
                                  "category": "J-POP",
                                  "isPublic": false,
                                  "items": [],
                                  "deletedItemIds": []
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(mapManageService).updateManagedMap(eq(1L), any(), eq(principal));
    }

    private UsernamePasswordAuthenticationToken authenticationToken(CustomPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER")
        );
    }
}