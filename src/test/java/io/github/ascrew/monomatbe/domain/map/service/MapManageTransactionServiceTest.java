package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapManageTransactionServiceTest {

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

    @Mock
    private MapItemJpaRepository mapItemJpaRepository;

    @Mock
    private MapPublicationValidator publicationValidator;

    @Mock
    private MapCacheEvictor mapCacheEvictor;

    @Mock
    private JsonMapper jsonMapper;

    private MapManageTransactionService mapManageTransactionService;

    @BeforeEach
    void setUp() {
        mapManageTransactionService = new MapManageTransactionService(
                quizMapJpaRepository,
                mapItemJpaRepository,
                publicationValidator,
                mapCacheEvictor,
                jsonMapper
        );
    }

    @Test
    void updateManagedMapInTransaction_success_updatesMapAndItemsAtomically() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);

        MapItem item1 = mapItem(
                100L,
                quizMap,
                1,
                "https://www.youtube.com/watch?v=old1",
                "old1",
                0,
                30,
                "[\"old1\"]"
        );
        MapItem item2 = mapItem(
                101L,
                quizMap,
                2,
                "https://www.youtube.com/watch?v=old2",
                "old2",
                30,
                60,
                "[\"old2\"]"
        );
        MapItem savedNewItem = savedNewItem(quizMap);

        ManageMapItemRequest updateItemRequest = new ManageMapItemRequest(
                100L,
                1,
                "https://www.youtube.com/watch?v=new1",
                10,
                40,
                List.of("ditto"),
                "ㄷㅌ",
                15
        );
        ManageMapItemRequest createItemRequest = new ManageMapItemRequest(
                null,
                2,
                "https://www.youtube.com/watch?v=new2",
                0,
                30,
                List.of("omg"),
                "ㅇㅇㅈ",
                15
        );

        ManageMapRequest request = new ManageMapRequest(
                "J-POP 퀴즈",
                "J-POP 중심 퀴즈 맵",
                MapCategory.JPOP,
                false,
                List.of(updateItemRequest, createItemRequest),
                List.of(101L)
        );

        List<PreparedManageItem> preparedItems = List.of(
                new PreparedManageItem(
                        updateItemRequest,
                        new YoutubeMetadata("new1", "YouTube title 1", "YouTube author 1", "https://thumbnail/1", null),
                        "[\"ditto\"]",
                        "ㄷㅌ",
                        15
                ),
                new PreparedManageItem(
                        createItemRequest,
                        new YoutubeMetadata("new2", "YouTube title 2", "YouTube author 2", "https://thumbnail/2", null),
                        "[\"omg\"]",
                        "ㅇㅇㅈ",
                        15
                )
        );

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item1, item2))
                .thenReturn(List.of(item1, item2));
        when(mapItemJpaRepository.save(any(MapItem.class))).thenReturn(savedNewItem);
        when(jsonMapper.readValue(eq("[\"ditto\"]"), any(TypeReference.class))).thenReturn(List.of("ditto"));
        when(jsonMapper.readValue(eq("[\"omg\"]"), any(TypeReference.class))).thenReturn(List.of("omg"));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapManageTransactionService.updateManagedMapInTransaction(1L, request, principal, preparedItems);

        assertThat(response.map().id()).isEqualTo(1L);
        assertThat(response.map().title()).isEqualTo("J-POP 퀴즈");
        assertThat(response.map().description()).isEqualTo("J-POP 중심 퀴즈 맵");
        assertThat(response.map().category()).isEqualTo(MapCategory.JPOP);
        assertThat(response.map().numOfSong()).isEqualTo(2);
        assertThat(response.map().totalPlayTime()).isEqualTo(60);
        assertThat(response.map().isPublic()).isFalse();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).id()).isEqualTo(100L);
        assertThat(response.items().get(1).id()).isEqualTo(200L);

        assertThat(quizMap.getTitle()).isEqualTo("J-POP 퀴즈");
        assertThat(quizMap.getDescription()).isEqualTo("J-POP 중심 퀴즈 맵");
        assertThat(quizMap.getCategory()).isEqualTo(MapCategory.JPOP);
        assertThat(quizMap.getNumOfSong()).isEqualTo(2);
        assertThat(quizMap.getTotalPlayTime()).isEqualTo(60);

        assertThat(item1.getYoutubeUrl()).isEqualTo("https://www.youtube.com/watch?v=new1");
        assertThat(item1.getVideoId()).isEqualTo("new1");
        assertThat(item1.getStartTime()).isEqualTo(10);
        assertThat(item1.getEndTime()).isEqualTo(40);
        assertThat(item1.getAnswers()).isEqualTo("[\"ditto\"]");

        assertThat(item2.getIsDeleted()).isTrue();

        verify(mapItemJpaRepository).setTemporaryOrderNums(1L);
        verify(mapItemJpaRepository).save(any(MapItem.class));
        verify(mapItemJpaRepository).flush();
        verify(mapCacheEvictor).evictPublicMapCaches(1L);
    }

    @Test
    void updateManagedMapInTransaction_requestItemAndDeletedItemDuplicated_returns400() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);
        MapItem item = mapItem(100L, quizMap, 1, "https://www.youtube.com/watch?v=old", "old", 0, 30, "[\"old\"]");

        ManageMapItemRequest itemRequest = new ManageMapItemRequest(
                100L,
                1,
                "https://www.youtube.com/watch?v=new",
                0,
                30,
                List.of("new"),
                "n",
                15
        );

        ManageMapRequest request = new ManageMapRequest(
                "title",
                "description",
                MapCategory.JPOP,
                false,
                List.of(itemRequest),
                List.of(100L)
        );

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageTransactionService.updateManagedMapInTransaction(
                1L,
                request,
                principal,
                List.of(new PreparedManageItem(
                        itemRequest,
                        new YoutubeMetadata("new", "title", "artist", "thumbnail", null),
                        "[\"new\"]",
                        "n",
                        15
                ))
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("수정할 문제와 삭제할 문제가 중복되었습니다.");

        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(anyLong());
    }

    @Test
    void updateManagedMapInTransaction_dataIntegrityViolation_returns409() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);
        MapItem item = mapItem(100L, quizMap, 1, "https://www.youtube.com/watch?v=old", "old", 0, 30, "[\"old\"]");

        ManageMapItemRequest itemRequest = new ManageMapItemRequest(
                100L,
                1,
                "https://www.youtube.com/watch?v=new",
                0,
                30,
                List.of("answer"),
                "hint",
                15
        );

        ManageMapRequest request = new ManageMapRequest(
                "new title",
                "new description",
                MapCategory.JPOP,
                false,
                List.of(itemRequest),
                List.of()
        );

        List<PreparedManageItem> preparedItems = List.of(new PreparedManageItem(
                itemRequest,
                new YoutubeMetadata("new", "title", "artist", "thumbnail", null),
                "[\"answer\"]",
                "hint",
                15
        ));

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item))
                .thenReturn(List.of(item));
        doThrow(new DataIntegrityViolationException("duplicate order"))
                .when(mapItemJpaRepository).flush();

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageTransactionService.updateManagedMapInTransaction(
                1L,
                request,
                principal,
                preparedItems
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("이미 사용 중인 문제 순서입니다.");

        verify(mapCacheEvictor, never()).evictPublicMapCaches(anyLong());
    }

    @Test
    void updateManagedMapInTransaction_preparedItemsSizeMismatch_throwsIllegalStateException() {
        ManageMapItemRequest itemRequest = new ManageMapItemRequest(
                100L,
                1,
                "https://www.youtube.com/watch?v=new",
                0,
                30,
                List.of("answer"),
                "hint",
                15
        );

        ManageMapRequest request = new ManageMapRequest(
                "new title",
                "new description",
                MapCategory.JPOP,
                false,
                List.of(itemRequest),
                List.of()
        );

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageTransactionService.updateManagedMapInTransaction(
                1L,
                request,
                principal,
                List.of()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("맵 관리 일괄 저장 준비 데이터가 요청 데이터와 일치하지 않습니다.");

        verify(quizMapJpaRepository, never()).findOwnedByIdAndIsDeletedFalseForUpdate(anyLong(), anyLong());
        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(anyLong());
    }

    @Test
    void updateManagedMapInTransaction_preparedItemRequestMismatch_throwsIllegalStateException() {
        ManageMapItemRequest requestItem = new ManageMapItemRequest(
                100L,
                1,
                "https://www.youtube.com/watch?v=request",
                0,
                30,
                List.of("answer"),
                "hint",
                15
        );

        ManageMapItemRequest preparedRequestItem = new ManageMapItemRequest(
                100L,
                1,
                "https://www.youtube.com/watch?v=prepared",
                0,
                30,
                List.of("answer"),
                "hint",
                15
        );

        ManageMapRequest request = new ManageMapRequest(
                "new title",
                "new description",
                MapCategory.JPOP,
                false,
                List.of(requestItem),
                List.of()
        );

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageTransactionService.updateManagedMapInTransaction(
                1L,
                request,
                principal,
                List.of(new PreparedManageItem(
                        preparedRequestItem,
                        new YoutubeMetadata("prepared", "title", "artist", "thumbnail", null),
                        "[\"answer\"]",
                        "hint",
                        15
                ))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("맵 관리 일괄 저장 준비 데이터가 요청 데이터와 일치하지 않습니다.");

        verify(quizMapJpaRepository, never()).findOwnedByIdAndIsDeletedFalseForUpdate(anyLong(), anyLong());
        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(anyLong());
    }

    private User registeredUser(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private QuizMap quizMap(User owner) {
        return QuizMap.builder()
                .id(1L)
                .owner(owner)
                .title("old map")
                .description("old description")
                .category(MapCategory.KPOP)
                .numOfSong(2)
                .totalPlayTime(60)
                .playCount(0L)
                .isPublic(false)
                .pendingPublic(false)
                .isDeleted(false)
                .build();
    }

    private MapItem mapItem(
            Long id,
            QuizMap quizMap,
            int orderNum,
            String youtubeUrl,
            String videoId,
            int startTime,
            int endTime,
            String answers
    ) {
        return MapItem.builder()
                .id(id)
                .map(quizMap)
                .orderNum(orderNum)
                .youtubeUrl(youtubeUrl)
                .videoId(videoId)
                .startTime(startTime)
                .endTime(endTime)
                .title("old title")
                .artist("old artist")
                .thumbnailUrl("old thumbnail")
                .answers(answers)
                .hint("old hint")
                .hintTime(15)
                .isDeleted(false)
                .build();
    }

    private MapItem savedNewItem(QuizMap quizMap) {
        return MapItem.builder()
                .id(200L)
                .map(quizMap)
                .orderNum(2)
                .youtubeUrl("https://www.youtube.com/watch?v=new2")
                .videoId("new2")
                .startTime(0)
                .endTime(30)
                .title("YouTube title 2")
                .artist("YouTube author 2")
                .thumbnailUrl("https://thumbnail/2")
                .answers("[\"omg\"]")
                .hint("ㅇㅇㅈ")
                .hintTime(15)
                .isDeleted(false)
                .build();
    }
}