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
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapItemPersistenceServiceTest {

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;
    @Mock
    private MapItemJpaRepository mapItemJpaRepository;
    @Mock
    private MapPublicationValidator publicationValidator;

    private MapItemPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new MapItemPersistenceService(
                quizMapJpaRepository,
                mapItemJpaRepository,
                publicationValidator
        );
    }

    @Test
    void findItemsForOwnedMap_notOwner_forbidden() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));

        assertThatThrownBy(() -> persistenceService.findItemsForOwnedMap(1L, 11L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("본인 소유의 맵만 문제를 관리할 수 있습니다.");

        verify(mapItemJpaRepository, never()).findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(any());
    }

    @Test
    void create_orderDuplicated_conflict() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalse(1L, 1)).thenReturn(true);

        CreateMapItemRequest request = createRequest(1);
        YoutubeMetadata metadata = new YoutubeMetadata("v", "t", "a", "th");

        assertThatThrownBy(() -> persistenceService.create(
                1L, 10L, request, metadata, "정답", "[]", "ㅈㄷ", 15
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("이미 사용 중인 문제 순서입니다.");

        verify(mapItemJpaRepository, never()).save(any());
    }

    @Test
    void create_success_recalculatesMetadata() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalse(1L, 1)).thenReturn(false);
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
                    .altAnswers(input.getAltAnswers())
                    .hint(input.getHint())
                    .hintTime(input.getHintTime())
                    .build();
        });
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(1L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(30L);

        CreateMapItemRequest request = createRequest(1);
        YoutubeMetadata metadata = new YoutubeMetadata("v", "t", "a", "th");

        MapItem saved = persistenceService.create(
                1L, 10L, request, metadata, "정답", "[]", "ㅈㄷ", 15
        );

        assertThat(saved.getId()).isEqualTo(100L);
        assertThat(saved.getVideoId()).isEqualTo("v");
        assertThat(quizMap.getNumOfSong()).isEqualTo(1);
        assertThat(quizMap.getTotalPlayTime()).isEqualTo(30);
    }

    @Test
    void delete_success_softDeletesAndRecalculatesMetadata() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem mapItem = MapItem.builder()
                .id(50L)
                .map(quizMap)
                .orderNum(1)
                .youtubeUrl("u")
                .videoId("v")
                .startTime(0)
                .endTime(30)
                .title("t")
                .artist("a")
                .thumbnailUrl("th")
                .answer("정답")
                .altAnswers(null)
                .hint("ㅈㄷ")
                .hintTime(15)
                .isDeleted(false)
                .build();
        when(mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(50L, 1L)).thenReturn(Optional.of(mapItem));
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(0L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(0L);

        persistenceService.delete(1L, 50L, 10L);

        assertThat(mapItem.getIsDeleted()).isTrue();
        assertThat(quizMap.getNumOfSong()).isZero();
        assertThat(quizMap.getTotalPlayTime()).isZero();
    }

    @Test
    void create_onPendingPublicMap_passesValidation_autoFlipToPublic() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        quizMap.setPendingPublicIntent(true);

        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalse(1L, 1)).thenReturn(false);
        when(mapItemJpaRepository.save(any(MapItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(1L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(30L);
        when(publicationValidator.isPublishable(1L)).thenReturn(true);

        persistenceService.create(
                1L, 10L, createRequest(1),
                new YoutubeMetadata("v", "t", "a", "th"),
                "정답", "[]", "ㅈㄷ", 15
        );

        assertThat(quizMap.getIsPublic()).isTrue();
        assertThat(quizMap.getPendingPublic()).isFalse();
    }

    @Test
    void create_onPendingPublicMap_failsValidation_remainsPrivate() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        quizMap.setPendingPublicIntent(true);

        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalse(1L, 1)).thenReturn(false);
        when(mapItemJpaRepository.save(any(MapItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(1L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(30L);
        when(publicationValidator.isPublishable(1L)).thenReturn(false);

        persistenceService.create(
                1L, 10L, createRequest(1),
                new YoutubeMetadata("v", "t", "a", "th"),
                "정답", "[]", "ㅈㄷ", 15
        );

        assertThat(quizMap.getIsPublic()).isFalse();
        assertThat(quizMap.getPendingPublic()).isTrue();
    }

    @Test
    void delete_lastItemFromPublicMap_autoFlipToPrivate_keepsPendingTrue() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        quizMap.markAsPublished(); // 공개 상태
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem mapItem = MapItem.builder()
                .id(50L)
                .map(quizMap)
                .orderNum(1)
                .youtubeUrl("u")
                .videoId("v")
                .startTime(0)
                .endTime(30)
                .title("t")
                .artist("a")
                .thumbnailUrl("th")
                .answer("정답")
                .altAnswers(null)
                .hint("ㅈㄷ")
                .hintTime(15)
                .isDeleted(false)
                .build();
        when(mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(50L, 1L)).thenReturn(Optional.of(mapItem));
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(0L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(0L);
        when(publicationValidator.isPublishable(1L)).thenReturn(false);

        persistenceService.delete(1L, 50L, 10L);

        assertThat(quizMap.getIsPublic()).isFalse();
        assertThat(quizMap.getPendingPublic()).isTrue();
    }

    @Test
    void delete_nonLastItemFromPublicMap_remainsPublic() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        quizMap.markAsPublished();
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem mapItem = MapItem.builder()
                .id(51L)
                .map(quizMap)
                .orderNum(2)
                .youtubeUrl("u")
                .videoId("v")
                .startTime(0)
                .endTime(30)
                .title("t")
                .artist("a")
                .thumbnailUrl("th")
                .answer("정답")
                .altAnswers(null)
                .hint("ㅈㄷ")
                .hintTime(15)
                .isDeleted(false)
                .build();
        when(mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(51L, 1L)).thenReturn(Optional.of(mapItem));
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(1L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(30L);
        when(publicationValidator.isPublishable(1L)).thenReturn(true);

        persistenceService.delete(1L, 51L, 10L);

        assertThat(quizMap.getIsPublic()).isTrue();
        assertThat(quizMap.getPendingPublic()).isFalse();
    }

    @Test
    void delete_publicMapBecomesInvalidNotEmpty_autoFlipToPrivate() {
        // 회귀 방어: numOfSong > 0 이어도 검증 미달이면 자동 비공개되어야 한다.
        // (데이터 오염, 수동 DB 수정, 향후 부분 수정 API 도입 등으로 공개 맵이 무효 상태에 놓이는 케이스)
        QuizMap quizMap = quizMap(1L, owner(10L));
        quizMap.markAsPublished();
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem mapItem = MapItem.builder()
                .id(60L)
                .map(quizMap)
                .orderNum(2)
                .youtubeUrl("u")
                .videoId("v")
                .startTime(0)
                .endTime(30)
                .title("t")
                .artist("a")
                .thumbnailUrl("th")
                .answer("정답")
                .altAnswers(null)
                .hint("ㅈㄷ")
                .hintTime(15)
                .isDeleted(false)
                .build();
        when(mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(60L, 1L)).thenReturn(Optional.of(mapItem));
        when(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(1L)).thenReturn(1L);
        when(mapItemJpaRepository.sumPlayTimeByMapId(1L)).thenReturn(30L);
        // 아이템이 남아있어도(numOfSong=1) 검증기가 실패를 알리면 자동 비공개되어야 한다.
        when(publicationValidator.isPublishable(1L)).thenReturn(false);

        persistenceService.delete(1L, 60L, 10L);

        assertThat(quizMap.getIsPublic()).isFalse();
        assertThat(quizMap.getPendingPublic()).isTrue();
    }

    @Test
    void reorder_success_assignsOrderNumsByRequestSequence() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem item1 = mapItem(1L, quizMap, 1);
        MapItem item2 = mapItem(2L, quizMap, 2);
        MapItem item3 = mapItem(3L, quizMap, 3);
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item1, item2, item3))
                .thenReturn(List.of(item1, item2, item3));

        // [3, 1, 2] 순서로 재정렬 요청
        persistenceService.reorder(1L, 10L, List.of(3L, 1L, 2L));

        verify(mapItemJpaRepository).setTemporaryOrderNums(eq(1L));
        assertThat(item3.getOrderNum()).isEqualTo(1);
        assertThat(item1.getOrderNum()).isEqualTo(2);
        assertThat(item2.getOrderNum()).isEqualTo(3);
    }


    @Test
    void reorder_notOwner_forbidden() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        assertThatThrownBy(() -> persistenceService.reorder(1L, 99L, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("본인 소유의 맵만 문제를 관리할 수 있습니다.");

        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(any());
    }

    @Test
    void reorder_duplicateItemId_badRequest() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem item1 = mapItem(1L, quizMap, 1);
        MapItem item2 = mapItem(2L, quizMap, 2);
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item1, item2));

        assertThatThrownBy(() -> persistenceService.reorder(1L, 10L, List.of(1L, 1L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("중복된 문제 ID가 있습니다.");

        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(any());
    }

    @Test
    void reorder_missingItem_badRequest() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem item1 = mapItem(1L, quizMap, 1);
        MapItem item2 = mapItem(2L, quizMap, 2);
        MapItem item3 = mapItem(3L, quizMap, 3);
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item1, item2, item3));

        // item3 누락
        assertThatThrownBy(() -> persistenceService.reorder(1L, 10L, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("모든 문제의 순서를 지정해야 합니다.");

        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(any());
    }

    @Test
    void reorder_invalidItemId_badRequest() {
        QuizMap quizMap = quizMap(1L, owner(10L));
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseForUpdate(1L)).thenReturn(Optional.of(quizMap));

        MapItem item1 = mapItem(1L, quizMap, 1);
        MapItem item2 = mapItem(2L, quizMap, 2);
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item1, item2));

        // 999L은 이 맵에 속하지 않는 ID
        assertThatThrownBy(() -> persistenceService.reorder(1L, 10L, List.of(1L, 999L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효하지 않은 문제 ID가 포함되어 있습니다.");

        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(any());
    }

    private MapItem mapItem(Long id, QuizMap quizMap, int orderNum) {
        return MapItem.builder()
                .id(id)
                .map(quizMap)
                .orderNum(orderNum)
                .youtubeUrl("u")
                .videoId("v")
                .startTime(0)
                .endTime(30)
                .title("t")
                .artist("a")
                .thumbnailUrl("th")
                .answer("정답")
                .altAnswers(null)
                .hint("ㅈㄷ")
                .hintTime(15)
                .isDeleted(false)
                .build();
    }

    private CreateMapItemRequest createRequest(int orderNum) {
        return new CreateMapItemRequest(
                orderNum,
                "https://www.youtube.com/watch?v=abcde123456",
                10,
                40,
                "정답",
                List.of(),
                null,
                null
        );
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
}
