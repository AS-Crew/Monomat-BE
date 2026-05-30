package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ReorderMapItemsRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import org.springframework.dao.DataIntegrityViolationException;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapItemServiceTest {

    @Mock
    private MapItemPersistenceService persistenceService;
    @Mock
    private YoutubeValidationService youtubeValidationService;
    @Mock
    private MapCacheEvictor mapCacheEvictor;
    @Mock
    private JsonMapper jsonMapper;

    private MapItemService mapItemService;

    @BeforeEach
    void setUp() {
        mapItemService = new MapItemService(
                persistenceService,
                youtubeValidationService,
                mapCacheEvictor,
                jsonMapper
        );
    }

    @Test
    void createMapItem_invalidTimeRange_skipsExternalCallsAndPersistence() {
        CreateMapItemRequest request = new CreateMapItemRequest(
                1,
                "https://www.youtube.com/watch?v=abcde123456",
                20,
                10,
                List.of("정답"),
                "힌트",
                15
        );

        assertThatThrownBy(() -> mapItemService.createMapItem(1L, request, principal(10L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("재생 구간은 시작 시간보다 종료 시간이 커야 합니다.");

        verify(youtubeValidationService, never()).validateYoutubeUrl(any());
        verify(persistenceService, never()).create(any(), any(), any(), any(), any(), any(), anyInt());
        verify(mapCacheEvictor, never()).evictPublicMapCaches(any());
    }

    @Test
    void createMapItem_success_callsYoutubeBeforePersistenceAndEvictsCacheAfter() throws Exception {
        // "좋은날"과 "좋은 날"은 정규화 후 동일 → dedup되어 단일 정답으로 저장된다.
        CreateMapItemRequest request = new CreateMapItemRequest(
                1,
                "https://www.youtube.com/watch?v=abcde123456",
                10,
                40,
                List.of("좋은날", "좋은 날"),
                "  좋은날 힌트  ",
                null
        );

        YoutubeMetadata metadata = new YoutubeMetadata("abcde123456", "title", "artist", "thumb");
        when(youtubeValidationService.validateYoutubeUrl(request.youtubeUrl())).thenReturn(metadata);
        when(jsonMapper.writeValueAsString(List.of("좋은날"))).thenReturn("[\"좋은날\"]");

        QuizMap quizMap = quizMap(1L, owner(10L));
        MapItem persisted = MapItem.builder()
                .id(100L)
                .map(quizMap)
                .orderNum(1)
                .youtubeUrl("https://www.youtube.com/watch?v=abcde123456")
                .videoId("abcde123456")
                .startTime(10)
                .endTime(40)
                .title("title")
                .artist("artist")
                .thumbnailUrl("thumb")
                .answers("[\"좋은날\"]")
                .hint("좋은날 힌트")
                .hintTime(15)
                .build();
        when(persistenceService.create(
                eq(1L),
                eq(10L),
                eq(request),
                eq(metadata),
                eq("[\"좋은날\"]"),
                eq("좋은날 힌트"),
                eq(15)
        )).thenReturn(persisted);

        var response = mapItemService.createMapItem(1L, request, principal(10L));

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.videoId()).isEqualTo("abcde123456");
        assertThat(response.hint()).isEqualTo("좋은날 힌트");

        // oEmbed → 영속화 → 캐시 무효화 순서 검증 (캐시 무효화는 영속화 메서드 정상 반환 = 커밋 후).
        InOrder inOrder = inOrder(youtubeValidationService, persistenceService, mapCacheEvictor);
        inOrder.verify(youtubeValidationService).validateYoutubeUrl(request.youtubeUrl());
        inOrder.verify(persistenceService).create(
                eq(1L), eq(10L), eq(request), eq(metadata),
                eq("[\"좋은날\"]"), eq("좋은날 힌트"), eq(15)
        );
        inOrder.verify(mapCacheEvictor).evictPublicMapCaches(1L);
    }

    @Test
    void getMapItems_propagatesPersistenceResponse() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        MapItem item = MapItem.builder()
                .id(50L)
                .map(quizMap)
                .orderNum(1)
                .youtubeUrl("https://www.youtube.com/watch?v=abcde123456")
                .videoId("abcde123456")
                .startTime(0)
                .endTime(30)
                .title("t")
                .artist("a")
                .thumbnailUrl("th")
                .answers(null)
                .hint("ㅈㄷ")
                .hintTime(15)
                .build();
        when(persistenceService.findItemsForOwnedMap(1L, 10L)).thenReturn(List.of(item));

        var response = mapItemService.getMapItems(1L, principal(10L));

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(50L);
    }

    @Test
    void reorderMapItems_success_callsPersistenceThenEvictsCache() {
        ReorderMapItemsRequest request = new ReorderMapItemsRequest(List.of(3L, 1L, 2L));

        mapItemService.reorderMapItems(1L, request, principal(10L));

        InOrder inOrder = inOrder(persistenceService, mapCacheEvictor);
        inOrder.verify(persistenceService).reorder(1L, 10L, List.of(3L, 1L, 2L));
        inOrder.verify(mapCacheEvictor).evictPublicMapCaches(1L);
    }

    @Test
    void reorderMapItems_dataIntegrityViolation_throwsConflict() {
        ReorderMapItemsRequest request = new ReorderMapItemsRequest(List.of(1L, 2L));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("unique"))
                .when(persistenceService).reorder(1L, 10L, List.of(1L, 2L));

        assertThatThrownBy(() -> mapItemService.reorderMapItems(1L, request, principal(10L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("이미 사용 중인 문제 순서입니다.");

        verify(mapCacheEvictor, never()).evictPublicMapCaches(any());
    }

    @Test
    void reorderMapItems_guestPrincipal_throwsForbiddenBeforePersistence() {
        ReorderMapItemsRequest request = new ReorderMapItemsRequest(List.of(1L, 2L));
        CustomPrincipal guest = new CustomPrincipal(10L, "u-10", UserType.GUEST);

        assertThatThrownBy(() -> mapItemService.reorderMapItems(1L, request, guest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("정식 회원만 맵 문제를 관리할 수 있습니다.");

        verify(persistenceService, never()).reorder(any(), any(), any());
        verify(mapCacheEvictor, never()).evictPublicMapCaches(any());
    }

    private User owner(Long id) {
        return User.builder()
                .id(id)
                .username("owner-" + id)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private QuizMap quizMap(Long mapId, User owner) {
        return QuizMap.builder()
                .id(mapId)
                .owner(owner)
                .title("map")
                .description("desc")
                .category(MapCategory.KPOP)
                .numOfSong(0)
                .totalPlayTime(0)
                .isPublic(false)
                .isDeleted(false)
                .build();
    }

    private CustomPrincipal principal(Long userId) {
        return new CustomPrincipal(userId, "u-" + userId, UserType.REGISTERED);
    }
}
