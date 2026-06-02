package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사용자 표시 정보 캐시 무효화 컴포넌트 검증.
 *
 * evictByUserId는 userIdentifier 조회를 한 번만 수행하고,
 * 각 식별자에 대해 채팅 발신자 프로필 캐시와 로비 닉네임 캐시를 함께 무효화해야 한다.
 */
class ChatSenderProfileCacheEvictorTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-id";
    private static final String GUEST_TOKEN = "guest-token";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final UserSessionRepository userSessionRepository = mock(UserSessionRepository.class);
    private final GuestSessionRepository guestSessionRepository = mock(GuestSessionRepository.class);

    private final ChatSenderProfileCacheEvictor evictor = new ChatSenderProfileCacheEvictor(
            redisTemplate,
            userSessionRepository,
            guestSessionRepository
    );

    @Test
    @DisplayName("evictByUserId는 각 식별자의 채팅 발신자 프로필 캐시와 로비 닉네임 캐시를 함께 삭제한다")
    void evictByUserId_deletesBothChatProfileAndNicknameCaches() {
        // given
        when(userSessionRepository.findSessionIdsByUserIdAndStatus(USER_ID, UserSessionStatus.ACTIVE))
                .thenReturn(List.of(SESSION_ID));
        when(guestSessionRepository.findGuestTokensByUserId(USER_ID))
                .thenReturn(List.of(GUEST_TOKEN));

        // when
        evictor.evictByUserId(USER_ID);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(captor.capture());

        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                RedisKeys.chatSenderProfileKey(SESSION_ID),
                RedisKeys.userNicknameKey(SESSION_ID),
                RedisKeys.chatSenderProfileKey(GUEST_TOKEN),
                RedisKeys.userNicknameKey(GUEST_TOKEN)
        );
    }

    @Test
    @DisplayName("userId가 null이면 캐시를 삭제하지 않는다")
    void evictByUserId_doesNothingWhenUserIdIsNull() {
        // when
        evictor.evictByUserId(null);

        // then
        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    @DisplayName("연결된 식별자가 없으면 캐시를 삭제하지 않는다")
    void evictByUserId_doesNothingWhenNoIdentifiers() {
        // given
        when(userSessionRepository.findSessionIdsByUserIdAndStatus(USER_ID, UserSessionStatus.ACTIVE))
                .thenReturn(List.of());
        when(guestSessionRepository.findGuestTokensByUserId(USER_ID))
                .thenReturn(List.of());

        // when
        evictor.evictByUserId(USER_ID);

        // then
        verify(redisTemplate, never()).delete(anyCollection());
    }
}
