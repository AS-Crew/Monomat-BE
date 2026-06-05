package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapServiceTest {

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MapPublicationValidator publicationValidator;
    @Mock
    private MapCacheEvictor mapCacheEvictor;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private JsonMapper jsonMapper;

    private MapService mapService;

    @BeforeEach
    void setUp() {
        mapService = new MapService(
                quizMapJpaRepository,
                userRepository,
                publicationValidator,
                mapCacheEvictor,
                redisTemplate,
                jsonMapper
        );

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void createMap_guestUser_forbidden() {
        CreateMapRequest request = new CreateMapRequest("title", "desc", MapCategory.KPOP, true);
        CustomPrincipal guest = new CustomPrincipal(1L, "guest-uuid", UserType.GUEST);

        assertThatThrownBy(() -> mapService.createMap(request, guest))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("정식 회원만 맵을 생성할 수 있습니다.");

        verify(userRepository, never()).findById(any());
    }

    @Test
    void createMap_registeredUser_success() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
        when(quizMapJpaRepository.save(any(QuizMap.class))).thenAnswer(invocation -> {
            QuizMap input = invocation.getArgument(0);
            return QuizMap.builder()
                    .id(300L)
                    .owner(input.getOwner())
                    .title(input.getTitle())
                    .description(input.getDescription())
                    .category(input.getCategory())
                    .isPublic(input.getIsPublic())
                    .pendingPublic(input.getPendingPublic())
                    .numOfSong(0)
                    .totalPlayTime(0)
                    .build();
        });

        CreateMapRequest request = new CreateMapRequest("new map", "desc", MapCategory.KPOP, true);
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.createMap(request, principal);

        assertThat(response.id()).isEqualTo(300L);
        assertThat(response.ownerId()).isEqualTo(10L);
        assertThat(response.ownerNickname()).isEqualTo("owner");
        assertThat(response.title()).isEqualTo("new map");
        // 생성 시 아이템이 0이므로 공개 의도는 pendingPublic 으로 보존되고 isPublic 은 false 로 저장된다.
        assertThat(response.isPublic()).isFalse();
        assertThat(response.pendingPublic()).isTrue();
        verify(mapCacheEvictor).evictPublicMapCaches(300L);
    }

    @Test
    void updateMap_notOwner_forbidden() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(100L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(true)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(100L)).thenReturn(Optional.of(quizMap));

        UpdateMapRequest request = new UpdateMapRequest("new", "new", MapCategory.JPOP, false);
        CustomPrincipal anotherUser = new CustomPrincipal(11L, "u-11", UserType.REGISTERED);

        assertThatThrownBy(() -> mapService.updateMap(100L, request, anotherUser))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("본인 소유의 맵만 수정/삭제할 수 있습니다.");
    }

    @Test
    void updateMap_owner_evictsPublicMapCaches() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(200L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(true)
                .pendingPublic(false)
                .numOfSong(0)
                .totalPlayTime(0)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(200L)).thenReturn(Optional.of(quizMap));

        UpdateMapRequest request = new UpdateMapRequest("new", "new", MapCategory.KPOP, false);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        mapService.updateMap(200L, request, ownerPrincipal);

        verify(mapCacheEvictor).evictPublicMapCaches(200L);
    }

    @Test
    void updateMap_toPublic_withNoItems_throws409() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(400L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(false)
                .pendingPublic(false)
                .numOfSong(0)
                .totalPlayTime(0)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(400L)).thenReturn(Optional.of(quizMap));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "공개를 위해서는 최소 1개의 문제가 필요합니다."))
                .when(publicationValidator).requirePublishable(400L);

        UpdateMapRequest request = new UpdateMapRequest("new", "new", MapCategory.KPOP, true);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        assertThatThrownBy(() -> mapService.updateMap(400L, request, ownerPrincipal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("최소 1개의 문제가 필요합니다");

        // 검증 실패 시 공개 상태는 바뀌지 않는다.
        assertThat(quizMap.getIsPublic()).isFalse();
    }

    @Test
    void updateMap_toPublic_withValidItems_marksAsPublished() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(500L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(false)
                .pendingPublic(false)
                .numOfSong(1)
                .totalPlayTime(30)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(500L)).thenReturn(Optional.of(quizMap));
        // requirePublishable 가 throw 하지 않으면 통과로 간주된다.

        UpdateMapRequest request = new UpdateMapRequest("new", "new", MapCategory.KPOP, true);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.updateMap(500L, request, ownerPrincipal);

        verify(publicationValidator).requirePublishable(500L);
        assertThat(quizMap.getIsPublic()).isTrue();
        assertThat(quizMap.getPendingPublic()).isFalse();
        assertThat(response.isPublic()).isTrue();
        assertThat(response.pendingPublic()).isFalse();
    }

    @Test
    void updateMap_toPrivate_clearsPendingPublic() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(600L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(false)
                .pendingPublic(true)
                .numOfSong(0)
                .totalPlayTime(0)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(600L)).thenReturn(Optional.of(quizMap));

        UpdateMapRequest request = new UpdateMapRequest("new", "new", MapCategory.KPOP, false);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        mapService.updateMap(600L, request, ownerPrincipal);

        verify(publicationValidator, never()).requirePublishable(any());
        assertThat(quizMap.getIsPublic()).isFalse();
        assertThat(quizMap.getPendingPublic()).isFalse();
    }

    @Test
    void updateMap_alreadyPublic_metadataOnly_doesNotCallValidator() {
        // 공개 → 공개 (메타데이터 수정만) 케이스: 검증을 다시 호출해선 안 된다.
        // 공개 상태의 무결성은 자동 토글이 보장하므로 단순 수정은 재검증 없이 통과.
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(700L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(true)
                .pendingPublic(false)
                .numOfSong(1)
                .totalPlayTime(30)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(700L)).thenReturn(Optional.of(quizMap));

        UpdateMapRequest request = new UpdateMapRequest("new title", "new desc", MapCategory.KPOP, true);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.updateMap(700L, request, ownerPrincipal);

        verify(publicationValidator, never()).requirePublishable(any());
        assertThat(quizMap.getIsPublic()).isTrue();
        assertThat(response.title()).isEqualTo("new title");
        assertThat(response.isPublic()).isTrue();
    }

    @Test
    void updateMap_alreadyPublicWithInvalidData_metadataUpdateSucceeds() {
        // 데이터 오염으로 isPublishable=false 인 공개 맵이어도, 단순 메타데이터 수정은
        // 검증 경로를 타지 않으므로 409 로 막히지 않고 통과해야 한다 (복구 경로 보장).
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(800L)
                .owner(owner)
                .title("old")
                .description("old")
                .category(MapCategory.POP)
                .isPublic(true)
                .pendingPublic(false)
                .numOfSong(1)
                .totalPlayTime(30)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(800L)).thenReturn(Optional.of(quizMap));
        // 검증기는 throw 하도록 stub 해두지만, 공개→공개 케이스라 실제로는 호출되지 않아야 한다.
        lenient().doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "오염 데이터"))
                .when(publicationValidator).requirePublishable(800L);

        UpdateMapRequest request = new UpdateMapRequest("fixed title", "fixed desc", MapCategory.KPOP, true);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.updateMap(800L, request, ownerPrincipal);

        verify(publicationValidator, never()).requirePublishable(any());
        assertThat(response.title()).isEqualTo("fixed title");
        assertThat(response.isPublic()).isTrue();
    }

    @Test
    void getMyMaps_includesDescriptionInSummary() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(100L)
                .owner(owner)
                .title("내 맵")
                .description("내 맵 설명")
                .category(MapCategory.KPOP)
                .numOfSong(3)
                .totalPlayTime(600)
                .isPublic(false)
                .pendingPublic(false)
                .build();

        when(quizMapJpaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(quizMap)));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.getMyMaps(0, 20, principal);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).mapId()).isEqualTo(100L);
        assertThat(response.content().get(0).description()).isEqualTo("내 맵 설명");
        assertThat(response.content().get(0).ownerId()).isEqualTo(10L);
        assertThat(response.content().get(0).ownerNickname()).isEqualTo("owner");
    }

    @Test
    void getMyMaps_nullDescription_returnedAsNull() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(101L)
                .owner(owner)
                .title("설명 없는 맵")
                .description(null)
                .category(MapCategory.KPOP)
                .numOfSong(0)
                .totalPlayTime(0)
                .isPublic(false)
                .pendingPublic(false)
                .build();

        when(quizMapJpaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(quizMap)));

        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.getMyMaps(0, 20, principal);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).description()).isNull();
        assertThat(response.content().get(0).ownerId()).isEqualTo(10L);
        assertThat(response.content().get(0).ownerNickname()).isEqualTo("owner");
    }

    @Test
    void getPublicMaps_includesDescriptionInSummary() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(200L)
                .owner(owner)
                .title("공개 맵")
                .description("공개 맵 설명")
                .category(MapCategory.KPOP)
                .numOfSong(5)
                .totalPlayTime(900)
                .isPublic(true)
                .pendingPublic(false)
                .build();

        when(quizMapJpaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(quizMap)));

        // keyword 를 넘기면 캐시를 우회하고 DB 조회 경로를 그대로 검증할 수 있다.
        var response = mapService.getPublicMaps(0, 20, "공개", null, null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).mapId()).isEqualTo(200L);
        assertThat(response.content().get(0).description()).isEqualTo("공개 맵 설명");
        assertThat(response.content().get(0).ownerId()).isEqualTo(10L);
        assertThat(response.content().get(0).ownerNickname()).isEqualTo("owner");
    }

    @Test
    void getPublicMap_includesOwnerNicknameInDetail() {
        User owner = User.builder()
                .id(10L)
                .username("owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        QuizMap quizMap = QuizMap.builder()
                .id(300L)
                .owner(owner)
                .title("상세 맵")
                .description("상세 맵 설명")
                .category(MapCategory.KPOP)
                .numOfSong(5)
                .totalPlayTime(900)
                .isPublic(true)
                .pendingPublic(false)
                .build();

        when(valueOperations.get(any(String.class))).thenReturn(null);
        when(quizMapJpaRepository.findByIdAndIsDeletedFalseAndIsPublicTrue(300L))
                .thenReturn(Optional.of(quizMap));

        var response = mapService.getPublicMap(300L);

        assertThat(response.id()).isEqualTo(300L);
        assertThat(response.ownerId()).isEqualTo(10L);
        assertThat(response.ownerNickname()).isEqualTo("owner");
        assertThat(response.title()).isEqualTo("상세 맵");
    }
    
    @Test
    void getPublicMap_cacheHit_returnsOwnerNickname() {
        MapDetailResponse cachedResponse = MapDetailResponse.builder()
                .id(300L)
                .ownerId(10L)
                .ownerNickname("owner")
                .title("상세 맵")
                .description("상세 맵 설명")
                .category(MapCategory.KPOP)
                .numOfSong(5)
                .totalPlayTime(900)
                .isPublic(true)
                .pendingPublic(false)
                .build();

        when(valueOperations.get(any(String.class))).thenReturn("{}");
        when(jsonMapper.readValue("{}", MapDetailResponse.class)).thenReturn(cachedResponse);

        var response = mapService.getPublicMap(300L);

        assertThat(response.id()).isEqualTo(300L);
        assertThat(response.ownerId()).isEqualTo(10L);
        assertThat(response.ownerNickname()).isEqualTo("owner");
        assertThat(response.title()).isEqualTo("상세 맵");

        verify(quizMapJpaRepository, never()).findByIdAndIsDeletedFalseAndIsPublicTrue(any());
    }
}
