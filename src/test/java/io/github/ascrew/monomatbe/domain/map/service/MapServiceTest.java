package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    private StringRedisTemplate redisTemplate;
    @Mock
    private JsonMapper jsonMapper;

    private MapService mapService;

    @BeforeEach
    void setUp() {
        mapService = new MapService(quizMapJpaRepository, userRepository, redisTemplate, jsonMapper);
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
                    .numOfSong(0)
                    .totalPlayTime(0)
                    .build();
        });

        CreateMapRequest request = new CreateMapRequest("new map", "desc", MapCategory.KPOP, true);
        CustomPrincipal principal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        var response = mapService.createMap(request, principal);

        assertThat(response.id()).isEqualTo(300L);
        assertThat(response.ownerId()).isEqualTo(10L);
        assertThat(response.title()).isEqualTo("new map");
        assertThat(response.isPublic()).isTrue();
        verify(redisTemplate).delete(argThat((java.util.Collection<String> keys) ->
                keys.contains(RedisKeys.mapPublicListKey())
                        && keys.contains(RedisKeys.mapPublicDetailKey(300L))
        ));
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
                .numOfSong(0)
                .totalPlayTime(0)
                .build();

        when(quizMapJpaRepository.findByIdAndIsDeletedFalse(200L)).thenReturn(Optional.of(quizMap));

        UpdateMapRequest request = new UpdateMapRequest("new", "new", MapCategory.KPOP, false);
        CustomPrincipal ownerPrincipal = new CustomPrincipal(10L, "u-10", UserType.REGISTERED);

        mapService.updateMap(200L, request, ownerPrincipal);

        verify(redisTemplate).delete(argThat((java.util.Collection<String> keys) ->
                keys.contains(RedisKeys.mapPublicListKey())
                        && keys.contains(RedisKeys.mapPublicDetailKey(200L))
        ));
    }
}
