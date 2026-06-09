package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbySettingsReconciliationRepositoryTest {

    private static final String CODE = "ABC123";
    private static final String REASON =
            "SETTINGS_UPDATE_RESTORE_FAILED:MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final LobbySettingsReconciliationRepository sut =
            new LobbySettingsReconciliationRepository(redisTemplate);

    @Test
    @DisplayName("로비 설정 재처리 payload를 전용 큐에 적재하고 성공 metric을 증가시킨다")
    void enqueueSettingsReconciliation_pushesPayloadAndIncrementsSuccessMetric() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        sut.enqueueSettingsReconciliation(CODE, REASON);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        verify(listOperations).rightPush(
                eq(RedisKeys.LOBBY_SETTINGS_RECONCILIATION_QUEUE),
                payloadCaptor.capture()
        );

        String payload = payloadCaptor.getValue();

        assertThat(payload).startsWith(CODE + "|" + REASON + "|0|");

        verify(valueOperations).increment(
                RedisKeys.METRIC_LOBBY_SETTINGS_RECONCILIATION_ENQUEUED
        );
    }

    @Test
    @DisplayName("로비 설정 재처리 큐 적재 실패 시 실패 metric을 증가시키고 예외를 전파하지 않는다")
    void enqueueSettingsReconciliation_incrementsFailedMetric_whenQueuePushFails() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doThrow(new RuntimeException("Redis 장애"))
                .when(listOperations)
                .rightPush(eq(RedisKeys.LOBBY_SETTINGS_RECONCILIATION_QUEUE), org.mockito.ArgumentMatchers.anyString());

        sut.enqueueSettingsReconciliation(CODE, REASON);

        verify(valueOperations).increment(
                RedisKeys.METRIC_LOBBY_SETTINGS_RECONCILIATION_FAILED
        );
    }
}