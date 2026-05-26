package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LobbyChatRateLimitServiceTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String CONTENT = "안녕하세요";

    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(1);
    private static final Duration REPEATED_MESSAGE_TTL = Duration.ofSeconds(5);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LobbyChatRateLimitService lobbyChatRateLimitService;

    @BeforeEach
    void setUp() {
        lobbyChatRateLimitService = new LobbyChatRateLimitService(redisTemplate);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("쿨타임과 반복 메시지 검증을 통과하면 최근 메시지 해시를 저장한다")
    void validateAndRecord_success() {
        // given
        String cooldownKey = RedisKeys.lobbyChatCooldownKey(LOBBY_CODE, USER_IDENTIFIER);
        String recentMessageKey = RedisKeys.lobbyChatRecentMessageKey(LOBBY_CODE, USER_IDENTIFIER);

        when(valueOperations.setIfAbsent(cooldownKey, "1", COOLDOWN_TTL))
                .thenReturn(true);
        when(valueOperations.get(recentMessageKey))
                .thenReturn(null);

        // when
        lobbyChatRateLimitService.validateAndRecord(
                LOBBY_CODE,
                USER_IDENTIFIER,
                CONTENT
        );

        // then
        verify(valueOperations).setIfAbsent(cooldownKey, "1", COOLDOWN_TTL);
        verify(valueOperations).get(recentMessageKey);
        verify(valueOperations).set(
                eq(recentMessageKey),
                eq(sha256(CONTENT)),
                eq(REPEATED_MESSAGE_TTL)
        );
    }

    @Test
    @DisplayName("쿨타임 key 획득에 실패하면 429로 차단한다")
    void validateAndRecord_failsWhenCooldownActive() {
        // given
        String cooldownKey = RedisKeys.lobbyChatCooldownKey(LOBBY_CODE, USER_IDENTIFIER);
        String recentMessageKey = RedisKeys.lobbyChatRecentMessageKey(LOBBY_CODE, USER_IDENTIFIER);

        when(valueOperations.setIfAbsent(cooldownKey, "1", COOLDOWN_TTL))
                .thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
                lobbyChatRateLimitService.validateAndRecord(
                        LOBBY_CODE,
                        USER_IDENTIFIER,
                        CONTENT
                )
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                );

        verify(valueOperations, never()).get(recentMessageKey);
        verify(valueOperations, never()).set(eq(recentMessageKey), eq(sha256(CONTENT)), eq(REPEATED_MESSAGE_TTL));
    }

    @Test
    @DisplayName("같은 메시지를 5초 이내 반복 전송하면 429로 차단한다")
    void validateAndRecord_failsWhenRepeatedMessage() {
        // given
        String cooldownKey = RedisKeys.lobbyChatCooldownKey(LOBBY_CODE, USER_IDENTIFIER);
        String recentMessageKey = RedisKeys.lobbyChatRecentMessageKey(LOBBY_CODE, USER_IDENTIFIER);

        when(valueOperations.setIfAbsent(cooldownKey, "1", COOLDOWN_TTL))
                .thenReturn(true);
        when(valueOperations.get(recentMessageKey))
                .thenReturn(sha256(CONTENT));

        // when & then
        assertThatThrownBy(() ->
                lobbyChatRateLimitService.validateAndRecord(
                        LOBBY_CODE,
                        USER_IDENTIFIER,
                        CONTENT
                )
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                );

        verify(valueOperations, never()).set(eq(recentMessageKey), eq(sha256(CONTENT)), eq(REPEATED_MESSAGE_TTL));
    }

    @Test
    @DisplayName("Redis 처리 중 예외가 발생하면 503으로 차단한다")
    void validateAndRecord_failsWhenRedisUnavailable() {
        // given
        String cooldownKey = RedisKeys.lobbyChatCooldownKey(LOBBY_CODE, USER_IDENTIFIER);

        when(valueOperations.setIfAbsent(cooldownKey, "1", COOLDOWN_TTL))
                .thenThrow(new RuntimeException("Redis unavailable"));

        // when & then
        assertThatThrownBy(() ->
                lobbyChatRateLimitService.validateAndRecord(
                        LOBBY_CODE,
                        USER_IDENTIFIER,
                        CONTENT
                )
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                );
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}