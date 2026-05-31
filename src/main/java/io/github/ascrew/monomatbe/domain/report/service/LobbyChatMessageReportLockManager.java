package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 로비 채팅 메시지 신고 중복 방지 lock manager
 *
 * [책임]
 * - 동일 사용자의 동일 로비/동일 메시지 신고가 동시에 들어올 때 하나만 통과시킨다.
 * - Redis SET NX + TTL 방식으로 짧은 lock을 잡는다.
 * - lock 획득 실패는 중복 요청으로 판단할 수 있게 false를 반환한다.
 *
 * [주의]
 * 이 lock은 영구 정합성 보장 수단이 아니라 동시 요청 race condition 완화 장치다.
 * 최종 중복 여부는 기존 PENDING 신고 조회로 한 번 더 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyChatMessageReportLockManager {

    private static final String LOCK_VALUE = "1";
    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(3);

    private final StringRedisTemplate redisTemplate;

    @Value("${monomat.report.lobby-chat-message.lock-ttl:PT3S}")
    private Duration lockTtl;

    /**
     * 채팅 메시지 신고 lock 획득을 시도한다.
     *
     * @param reporterId 신고자 users.id
     * @param lobbyId 로비 ID
     * @param messageId 채팅 메시지 ID
     * @return lock 획득 성공 여부
     */
    public boolean tryLock(Long reporterId, Long lobbyId, String messageId) {
        String key = RedisKeys.lobbyChatMessageReportLockKey(
                reporterId,
                lobbyId,
                messageId
        );

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                key,
                LOCK_VALUE,
                effectiveLockTtl()
        );

        return Boolean.TRUE.equals(locked);
    }

    /**
     * 채팅 메시지 신고 lock을 해제한다.
     *
     * @param reporterId 신고자 users.id
     * @param lobbyId 로비 ID
     * @param messageId 채팅 메시지 ID
     */
    public void unlock(Long reporterId, Long lobbyId, String messageId) {
        String key = RedisKeys.lobbyChatMessageReportLockKey(
                reporterId,
                lobbyId,
                messageId
        );

        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn(
                    "로비 채팅 메시지 신고 lock 해제 실패 - TTL 만료 대기. reporterId: {}, lobbyId: {}, messageId: {}",
                    reporterId,
                    lobbyId,
                    messageId,
                    e
            );
        }
    }

    private Duration effectiveLockTtl() {
        if (lockTtl == null || lockTtl.isZero() || lockTtl.isNegative()) {
            return DEFAULT_LOCK_TTL;
        }

        return lockTtl;
    }
}