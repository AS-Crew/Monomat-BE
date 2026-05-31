package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 로비 채팅 메시지 신고 중복 방지 lock manager
 *
 * [책임]
 * - 동일 사용자의 동일 로비/동일 메시지 신고가 동시에 들어올 때 하나만 통과시킨다.
 * - Redis SET NX + TTL 방식으로 짧은 lock을 잡는다.
 * - 요청별 고유 token을 lock value로 저장한다.
 * - unlock 시 Lua compare-and-delete로 lock 소유자만 삭제한다.
 *
 * [주의]
 * Redis lock은 race condition 완화 장치다.
 * 최종 중복 방어는 DB unique 제약으로 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyChatMessageReportLockManager {

    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(30);

    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${monomat.report.lobby-chat-message.lock-ttl:PT30S}")
    private Duration lockTtl;

    /**
     * 채팅 메시지 신고 lock 획득을 시도한다.
     *
     * @param reporterId 신고자 users.id
     * @param lobbyId 로비 ID
     * @param messageId 채팅 메시지 ID
     * @return lock 획득 성공 시 lock 소유권 정보
     */
    public Optional<LobbyChatMessageReportLock> tryLock(
            Long reporterId,
            Long lobbyId,
            String messageId
    ) {
        String key = RedisKeys.lobbyChatMessageReportLockKey(
                reporterId,
                lobbyId,
                messageId
        );
        String token = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                key,
                token,
                effectiveLockTtl()
        );

        if (!Boolean.TRUE.equals(locked)) {
            return Optional.empty();
        }

        return Optional.of(new LobbyChatMessageReportLock(
                key,
                token,
                reporterId,
                lobbyId,
                messageId
        ));
    }

    /**
     * 채팅 메시지 신고 lock을 해제한다.
     *
     * 저장된 token이 현재 요청의 token과 일치할 때만 삭제한다.
     * TTL 만료 후 다른 요청이 같은 key로 lock을 재획득한 경우, 기존 요청은 해당 lock을 삭제하지 못한다.
     *
     * @param lock lock 소유권 정보
     */
    public void unlock(LobbyChatMessageReportLock lock) {
        try {
            redisTemplate.execute(
                    UNLOCK_SCRIPT,
                    List.of(lock.key()),
                    lock.token()
            );
        } catch (RuntimeException e) {
            log.warn(
                    "로비 채팅 메시지 신고 lock 해제 실패 - TTL 만료 대기. reporterId: {}, lobbyId: {}, messageId: {}",
                    lock.reporterId(),
                    lock.lobbyId(),
                    lock.messageId(),
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