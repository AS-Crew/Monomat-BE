package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapManageServiceTest {

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

    @Mock
    private YoutubeValidationService youtubeValidationService;

    @Mock
    private MapManageTransactionService mapManageTransactionService;

    @Mock
    private JsonMapper jsonMapper;

    private MapManageService mapManageService;

    @BeforeEach
    void setUp() {
        mapManageService = new MapManageService(
                quizMapJpaRepository,
                youtubeValidationService,
                mapManageTransactionService,
                jsonMapper
        );
    }

    @Test
    void updateManagedMap_success_preparesYoutubeMetadataBeforeTransaction() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);

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
                        )
                ),
                List.of()
        );

        ManageMapResponse expectedResponse = ManageMapResponse.builder()
                .map(MapDetailResponse.builder()
                        .id(1L)
                        .ownerId(10L)
                        .ownerNickname("owner")
                        .title("J-POP 퀴즈")
                        .description("J-POP 중심 퀴즈 맵")
                        .category(MapCategory.JPOP)
                        .numOfSong(1)
                        .totalPlayTime(30)
                        .isPublic(false)
                        .pendingPublic(false)
                        .playCount(0L)
                        .createdAt(LocalDateTime.of(2026, 6, 6, 12, 0))
                        .updatedAt(LocalDateTime.of(2026, 6, 6, 12, 10))
                        .build())
                .items(List.of())
                .build();

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));
        when(youtubeValidationService.validateYoutubeUrl("https://www.youtube.com/watch?v=new1"))
                .thenReturn(new YoutubeMetadata("new1", "YouTube title", "YouTube author", "https://thumbnail", null));
        when(jsonMapper.writeValueAsString(List.of("ditto"))).thenReturn("[\"ditto\"]");
        when(mapManageTransactionService.updateManagedMapInTransaction(eq(1L), eq(request), eq(principal), any()))
                .thenReturn(expectedResponse);

        ManageMapResponse response = mapManageService.updateManagedMap(1L, request, principal);

        assertThat(response).isSameAs(expectedResponse);

        ArgumentCaptor<List<PreparedManageItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapManageTransactionService).updateManagedMapInTransaction(
                eq(1L),
                eq(request),
                eq(principal),
                captor.capture()
        );

        List<PreparedManageItem> preparedItems = captor.getValue();
        assertThat(preparedItems).hasSize(1);
        assertThat(preparedItems.get(0).metadata().videoId()).isEqualTo("new1");
        assertThat(preparedItems.get(0).answersJson()).isEqualTo("[\"ditto\"]");
        assertThat(preparedItems.get(0).hint()).isEqualTo("ㄷㅌ");
        assertThat(preparedItems.get(0).hintTime()).isEqualTo(15);

        verify(youtubeValidationService).validateYoutubeUrl("https://www.youtube.com/watch?v=new1");
    }

    @Test
    void updateManagedMap_guestUser_forbidden() {
        ManageMapRequest request = validEmptyRequest();
        CustomPrincipal guest = new CustomPrincipal(20L, "guest-20", UserType.GUEST);

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, guest))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("정식 회원만 맵을 관리할 수 있습니다.");

        verify(quizMapJpaRepository, never()).findByIdAndIsDeletedFalse(anyLong());
        verify(mapManageTransactionService, never()).updateManagedMapInTransaction(anyLong(), any(), any(), any());
    }

    @Test
    void updateManagedMap_notOwner_forbidden() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);

        ManageMapRequest request = validEmptyRequest();
        CustomPrincipal anotherUser = new CustomPrincipal(99L, "u-99", UserType.REGISTERED);

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, anotherUser))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("본인 소유의 맵만 수정할 수 있습니다.");

        verify(mapManageTransactionService, never()).updateManagedMapInTransaction(anyLong(), any(), any(), any());
    }

    @Test
    void updateManagedMap_mapNotFound_returns404() {
        ManageMapRequest request = validEmptyRequest();
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("맵을 찾을 수 없습니다.");

        verify(mapManageTransactionService, never()).updateManagedMapInTransaction(anyLong(), any(), any(), any());
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
        verify(mapManageTransactionService, never()).updateManagedMapInTransaction(anyLong(), any(), any(), any());
    }

    @Test
    void updateManagedMap_youtubeValidationFailure_doesNotEnterTransaction() {
        User owner = registeredUser(10L, "owner");
        QuizMap quizMap = quizMap(owner);

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

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(1L)).thenReturn(Optional.of(quizMap));
        when(youtubeValidationService.validateYoutubeUrl("https://www.youtube.com/watch?v=invalid"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "YouTube URL 검증 실패"));

        assertThatThrownBy(() -> mapManageService.updateManagedMap(1L, request, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("YouTube URL 검증 실패");

        verify(mapManageTransactionService, never()).updateManagedMapInTransaction(anyLong(), any(), any(), any());
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
}