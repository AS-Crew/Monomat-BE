package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.game.config.GameSessionProperties;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class MapPlayCountService {

    private final StringRedisTemplate redisTemplate;
    private final QuizMapJpaRepository quizMapJpaRepository;
    private final MapCacheEvictor mapCacheEvictor;
    private final GameSessionProperties gameSessionProperties;

    /**
     * 특정 로비에서 선택된 맵의 플레이 횟수를 한 번만 증가시킨다.
     *
     * [정책]
     * - 로비 생성 시점에는 증가하지 않는다.
     * - 게임 세션 생성이 성공한 뒤 호출되어야 한다.
     * - 동일 lobbyCode 기준으로 SETNX가 성공한 경우에만 DB playCount를 증가시킨다.
     * - DB 증가 실패 또는 트랜잭션 롤백 시 Redis 중복 방지 키를 삭제해 재시도 가능 상태로 되돌린다.
     *
     * @param lobbyCode 로비 초대 코드
     * @param mapId 게임에 사용된 맵 ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countOnce(String lobbyCode, Long mapId) {
        if (lobbyCode == null || lobbyCode.isBlank()) {
            throw new IllegalArgumentException("lobbyCode must not be blank");
        }

        if (mapId == null) {
            throw new IllegalArgumentException("mapId must not be null");
        }

        String countedKey = RedisKeys.lobbyMapPlayCountedKey(lobbyCode);
        Boolean counted = redisTemplate.opsForValue().setIfAbsent(
                countedKey,
                String.valueOf(mapId),
                gameSessionProperties.getRedisTtl()
        );

        if (!Boolean.TRUE.equals(counted)) {
            log.info(
                    "맵 플레이 횟수 중복 집계 방지 - lobbyCode: {}, mapId: {}",
                    lobbyCode,
                    mapId
            );
            return;
        }

        registerRollbackCleanup(countedKey, lobbyCode, mapId);

        try {
            int updated = quizMapJpaRepository.increasePlayCount(mapId);

            if (updated != 1) {
                throw new IllegalStateException("맵 플레이 횟수 증가 대상이 존재하지 않습니다. mapId=" + mapId);
            }

            mapCacheEvictor.evictPublicMapCaches(mapId);

            log.info(
                    "맵 플레이 횟수 증가 완료 - lobbyCode: {}, mapId: {}",
                    lobbyCode,
                    mapId
            );
        } catch (RuntimeException e) {
            deleteDedupKey(countedKey, lobbyCode, mapId);
            throw e;
        }
    }

    private void registerRollbackCleanup(String countedKey, String lobbyCode, Long mapId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn(
                    "맵 플레이 횟수 롤백 보상 등록 실패 - 트랜잭션 동기화 비활성. lobbyCode: {}, mapId: {}, key: {}",
                    lobbyCode,
                    mapId,
                    countedKey
            );
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteDedupKey(countedKey, lobbyCode, mapId);
                }
            }
        });
    }

    private void deleteDedupKey(String countedKey, String lobbyCode, Long mapId) {
        try {
            redisTemplate.delete(countedKey);
        } catch (RuntimeException deleteException) {
            log.error(
                    "맵 플레이 횟수 보상 키 삭제 실패 - lobbyCode: {}, mapId: {}, key: {}",
                    lobbyCode,
                    mapId,
                    countedKey,
                    deleteException
            );
        }
    }
}