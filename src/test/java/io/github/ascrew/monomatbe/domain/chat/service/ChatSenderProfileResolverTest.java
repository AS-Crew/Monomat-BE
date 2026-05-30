package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserIdentifierProfile;
import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSenderProfileResolverTest {

    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String CACHE_KEY = RedisKeys.chatSenderProfileKey(USER_IDENTIFIER);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final JsonMapper jsonMapper = new JsonMapper();
    private final UserNicknameLookupService userNicknameLookupService = mock(UserNicknameLookupService.class);

    private ChatSenderProfileResolver resolver;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        resolver = new ChatSenderProfileResolver(
                redisTemplate,
                jsonMapper,
                userNicknameLookupService
        );
    }

    @Test
    @DisplayName("캐시에 프로필이 있으면 DB 조회 없이 캐시 값을 반환한다")
    void resolve_returnsCachedProfile() throws Exception {
        // given
        ChatSenderProfile cached = ChatSenderProfile.builder()
                .userIdentifier(USER_IDENTIFIER)
                .userId(1L)
                .nickname("캐시닉네임")
                .build();

        when(valueOperations.get(CACHE_KEY))
                .thenReturn(jsonMapper.writeValueAsString(cached));

        // when
        ChatSenderProfile result = resolver.resolve(USER_IDENTIFIER);

        // then
        assertThat(result.getUserIdentifier()).isEqualTo(USER_IDENTIFIER);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getNickname()).isEqualTo("캐시닉네임");

        verify(userNicknameLookupService, never()).findProfileMapByUserIdentifiers(any());
    }

    @Test
    @DisplayName("캐시가 없으면 DB 조회 후 Redis에 캐시한다")
    void resolve_loadsAndCachesWhenCacheMiss() {
        // given
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(userNicknameLookupService.findProfileMapByUserIdentifiers(List.of(USER_IDENTIFIER)))
                .thenReturn(Map.of(
                        USER_IDENTIFIER,
                        new UserIdentifierProfile(1L, "조회닉네임")
                ));

        // when
        ChatSenderProfile result = resolver.resolve(USER_IDENTIFIER);

        // then
        assertThat(result.getUserIdentifier()).isEqualTo(USER_IDENTIFIER);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getNickname()).isEqualTo("조회닉네임");

        verify(valueOperations).set(
                eq(CACHE_KEY),
                any(String.class),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("프로필 조회 결과가 없으면 unresolved profile을 캐시한다")
    void resolve_cachesUnresolvedProfileWhenProfileMissing() {
        // given
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(userNicknameLookupService.findProfileMapByUserIdentifiers(List.of(USER_IDENTIFIER)))
                .thenReturn(Map.of());

        // when
        ChatSenderProfile result = resolver.resolve(USER_IDENTIFIER);

        // then
        assertThat(result.getUserIdentifier()).isEqualTo(USER_IDENTIFIER);
        assertThat(result.getUserId()).isNull();
        assertThat(result.getNickname()).isNull();

        verify(valueOperations).set(
                eq(CACHE_KEY),
                any(String.class),
                any(Duration.class)
        );
    }
}