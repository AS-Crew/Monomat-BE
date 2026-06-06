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
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapManageServiceTest {

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

    @Mock
    private MapItemJpaRepository mapItemJpaRepository;

    @Mock
    private YoutubeValidationService youtubeValidationService;

    @Mock
    private MapPublicationValidator publicationValidator;

    @Mock
    private MapCacheEvictor mapCacheEvictor;

    @Mock
    private JsonMapper jsonMapper;

    private MapManageService mapManageService;

    @BeforeEach
    void setUp() {
        mapManageService = new MapManageService(
                quizMapJpaRepository,
                mapItemJpaRepository,
                youtubeValidationService,
                publicationValidator,
                mapCacheEvictor,
                jsonMapper
        );
    }

    @Test
    void updateManagedMap_success_updatesMapAndItemsAtomically() {
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

        ManageMapRequest request = new ManageMapRequest(
                "J-POP 퀴즈",
                "J-POP 중심 퀴즈 맵",
                MapCategory.JPOP,
                false,
                List.of(
                        new ManageMapItemRequest(
                                100L,
                                1,
                                "https://www.youtube.com/watch?v=new1",
                                10,
                                40,
                                List.of(" Ditto ", "ditto"),
                                "ㄷㅌ",
                                15
                        ),
                        new ManageMapItemRequest(
                                null,
                                2,
                                "https://www.youtube.com/watch?v=new2",
                                0,
                                30,
                                List.of("OMG"),
                                "ㅇㅇㅈ",
                                15
                        )
                ),
                List.of(101L)
        );

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item1, item2))
                .thenReturn(List.of(item1, item2))
                .thenReturn(List.of(item1, savedNewItem(quizMap)));
        when(youtubeValidationService.validateYoutubeUrl("https://www.youtube.com/watch?v=new1"))
                .thenReturn(new YoutubeMetadata("new1", "YouTube title 1", "YouTube author 1", "https://thumbnail/1", null));
        when(youtubeValidationService.validateYoutubeUrl("https://www.youtube.com/watch?v=new2"))
                .thenReturn(new YoutubeMetadata("new2", "YouTube title 2", "YouTube author 2", "https://thumbnail/2", null));
        when(jsonMapper.writeValueAsString(List.of("ditto"))).thenReturn("[\"ditto\"]");
        when(jsonMapper.writeValueAsString(List.of("omg"))).thenReturn("[\"omg\"]");
        when(jsonMapper.readValue(eq("[\"ditto\"]"), any(TypeReference.class))).thenReturn(List.of("ditto"));
        when(jsonMapper.readValue(eq("[\"omg\"]"), any(TypeReference.class))).thenReturn(List.of("omg"));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapManageService.updateManagedMap(1L, request, principal);

        assertThat(response.map().id()).isEqualTo(1L);
        assertThat(response.map().title()).isEqualTo("J-POP 퀴즈");
        assertThat(response.map().description()).isEqualTo("J-POP 중심 퀴즈 맵");
        assertThat(response.map().category()).isEqualTo(MapCategory.JPOP);
        assertThat(response.map().numOfSong()).isEqualTo(2);
        assertThat(response.map().totalPlayTime()).isEqualTo(60);
        assertThat(response.map().isPublic()).isFalse();
        assertThat(response.items()).hasSize(2);

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
    void updateManagedMap_guestUser_forbidden() {
        ManageMapRequest request = validEmptyRequest();
        CustomPrincipal guest = new CustomPrincipal(20L, "guest-20", UserType.GUEST);

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, guest))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("정식 회원만 맵을 관리할 수 있습니다.");

        verify(quizMapJpaRepository, never()).findOwnedByIdAndIsDeletedFalseForUpdate(anyLong(), anyLong());
    }

    @Test
    void updateManagedMap_notOwner_forbidden() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);

        ManageMapRequest request = validEmptyRequest();
        CustomPrincipal anotherUser = new CustomPrincipal(99L, "u-99", UserType.REGISTERED);

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 99L))
                .thenReturn(Optional.empty());
        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L))
                .thenReturn(Optional.of(quizMap));

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, anotherUser))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("본인 소유의 맵만 수정할 수 있습니다.");
    }

    @Test
    void updateManagedMap_mapNotFound_returns404() {
        ManageMapRequest request = validEmptyRequest();
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.empty());
        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("맵을 찾을 수 없습니다.");
    }

    @Test
    void updateManagedMap_duplicateOrder_returns400() {
        ManageMapRequest request = new ManageMapRequest(
                "title",
                "description",
                MapCategory.JPOP,
                false,
                List.of(
                        new ManageMapItemRequest(null, 1, "https://www.youtube.com/watch?v=a", 0, 30, List.of("a"), "a", 15),
                        new ManageMapItemRequest(null, 1, "https://www.youtube.com/watch?v=b", 0, 30, List.of("b"), "b", 15)
                ),
                List.of()
        );
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("중복된 문제 순서가 있습니다.");

        verify(youtubeValidationService, never()).validateYoutubeUrl(any());
    }

    @Test
    void updateManagedMap_requestItemAndDeletedItemDuplicated_returns400() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);
        MapItem item = mapItem(100L, quizMap, 1, "https://www.youtube.com/watch?v=old", "old", 0, 30, "[\"old\"]");

        ManageMapRequest request = new ManageMapRequest(
                "title",
                "description",
                MapCategory.JPOP,
                false,
                List.of(new ManageMapItemRequest(
                        100L,
                        1,
                        "https://www.youtube.com/watch?v=new",
                        0,
                        30,
                        List.of("new"),
                        "n",
                        15
                )),
                List.of(100L)
        );

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("수정할 문제와 삭제할 문제가 중복되었습니다.");

        verify(youtubeValidationService, never()).validateYoutubeUrl(any());
    }

    @Test
    void updateManagedMap_youtubeValidationFailure_rollsBackBeforeMutation() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);
        MapItem item = mapItem(100L, quizMap, 1, "https://www.youtube.com/watch?v=old", "old", 0, 30, "[\"old\"]");

        ManageMapRequest request = new ManageMapRequest(
                "new title",
                "new description",
                MapCategory.JPOP,
                false,
                List.of(new ManageMapItemRequest(
                        100L,
                        1,
                        "https://www.youtube.com/watch?v=invalid",
                        0,
                        30,
                        List.of("answer"),
                        "hint",
                        15
                )),
                List.of()
        );

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item));
        when(youtubeValidationService.validateYoutubeUrl("https://www.youtube.com/watch?v=invalid"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "YouTube URL 검증 실패"));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("YouTube URL 검증 실패");

        assertThat(quizMap.getTitle()).isEqualTo("old map");
        assertThat(item.getYoutubeUrl()).isEqualTo("https://www.youtube.com/watch?v=old");

        verify(mapItemJpaRepository, never()).setTemporaryOrderNums(anyLong());
        verify(mapCacheEvictor, never()).evictPublicMapCaches(anyLong());
    }

    @Test
    void updateManagedMap_dataIntegrityViolation_returns409() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);
        MapItem item = mapItem(100L, quizMap, 1, "https://www.youtube.com/watch?v=old", "old", 0, 30, "[\"old\"]");

        ManageMapRequest request = new ManageMapRequest(
                "new title",
                "new description",
                MapCategory.JPOP,
                false,
                List.of(new ManageMapItemRequest(
                        100L,
                        1,
                        "https://www.youtube.com/watch?v=new",
                        0,
                        30,
                        List.of("answer"),
                        "hint",
                        15
                )),
                List.of()
        );

        when(quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(1L, 10L))
                .thenReturn(Optional.of(quizMap))
                .thenReturn(Optional.of(quizMap));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item))
                .thenReturn(List.of(item));
        when(youtubeValidationService.validateYoutubeUrl("https://www.youtube.com/watch?v=new"))
                .thenReturn(new YoutubeMetadata("new", "title", "artist", "thumbnail", null));
        when(jsonMapper.writeValueAsString(List.of("answer"))).thenReturn("[\"answer\"]");
        doThrow(new DataIntegrityViolationException("duplicate order"))
                .when(mapItemJpaRepository).flush();

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("이미 사용 중인 문제 순서입니다.");

        verify(mapCacheEvictor, never()).evictPublicMapCaches(anyLong());
    }

    private ManageMapRequest validEmptyRequest() {
        return new ManageMapRequest(
                "title",
                "description",
                MapCategory.JPOP,
                false,
                List.of(),
                List.of()
        );
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