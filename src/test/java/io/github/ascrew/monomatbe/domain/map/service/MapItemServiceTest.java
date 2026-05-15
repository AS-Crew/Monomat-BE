package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapItemServiceTest {

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;
    @Mock
    private MapItemJpaRepository mapItemJpaRepository;
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
                quizMapJpaRepository,
                mapItemJpaRepository,
                youtubeValidationService,
                mapCacheEvictor,
                jsonMapper
        );
    }

    @Test
    void createMapItem_invalidTimeRange_badRequest() {
        CreateMapItemRequest request = new CreateMapItemRequest(
                1,
                "https://www.youtube.com/watch?v=abcde123456",
                20,
                10,
                "정답",
                List.of("정답2"),
                null,
                15
        );

        User owner = owner(10L);
        QuizMap quizMap = quizMap(1L, owner);
        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));

        assertThatThrownBy(() -> mapItemService.createMapItem(1L, request, principal(10L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("재생 구간은 시작 시간보다 종료 시간이 커야 합니다.");

        verify(mapItemJpaRepository, never()).save(any());
    }

    @Test
    void createMapItem_success_recalculatesMetadataAndEvictsCache() throws Exception {
        CreateMapItemRequest request = new CreateMapItemRequest(
                1,
                "https://www.youtube.com/watch?v=abcde123456",
                10,
                40,
                "좋은날",
                List.of("좋은 날", "좋은날"),
                null,
                null
        );

        User owner = owner(10L);
        QuizMap quizMap = quizMap(1L, owner);
        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalse(1L, 1)).thenReturn(false);
        when(youtubeValidationService.validateYoutubeUrl(request.youtubeUrl()))
                .thenReturn(new YoutubeMetadata("abcde123456", "title", "artist", "thumb"));
        when(jsonMapper.writeValueAsString(any())).thenReturn("[\"좋은 날\",\"좋은날\"]");
        when(mapItemJpaRepository.save(any(MapItem.class))).thenAnswer(invocation -> {
            MapItem input = invocation.getArgument(0);
            return MapItem.builder()
                    .id(100L)
                    .map(input.getMap())
                    .orderNum(input.getOrderNum())
                    .youtubeUrl(input.getYoutubeUrl())
                    .videoId(input.getVideoId())
                    .startTime(input.getStartTime())
                    .endTime(input.getEndTime())
                    .title(input.getTitle())
                    .artist(input.getArtist())
                    .thumbnailUrl(input.getThumbnailUrl())
                    .answer(input.getAnswer())
                    .altAnswers(null)
                    .hint(input.getHint())
                    .hintTime(input.getHintTime())
                    .build();
        });
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(1L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(30L);

        var response = mapItemService.createMapItem(1L, request, principal(10L));

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.videoId()).isEqualTo("abcde123456");
        assertThat(response.hint()).isEqualTo("ㅈㅇㄴ");
        assertThat(quizMap.getNumOfSong()).isEqualTo(1);
        assertThat(quizMap.getTotalPlayTime()).isEqualTo(30);
        verify(mapCacheEvictor).evictPublicMapCaches(1L);
    }

    @Test
    void getMapItems_notOwner_forbidden() {
        User owner = owner(10L);
        QuizMap quizMap = quizMap(1L, owner);
        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));

        assertThatThrownBy(() -> mapItemService.getMapItems(1L, principal(11L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("본인 소유의 맵만 문제를 관리할 수 있습니다.");
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
