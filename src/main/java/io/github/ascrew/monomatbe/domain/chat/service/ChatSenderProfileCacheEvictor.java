package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 사용자 표시 정보(닉네임) 캐시 무효화 컴포넌트
 *
 * [책임]
 * - 닉네임 변경 등 사용자 표시 정보가 변경된 경우, userIdentifier 기준으로 캐싱된
 *   사용자 표시 정보 캐시를 Redis에서 제거한다.
 * - 무효화 대상은 두 종류다.
 *   1) 채팅 발신자 프로필 캐시 (chat:sender-profile:{userIdentifier})
 *   2) 로비 목록/상세 닉네임 캐시 (user:nickname:{sha256(userIdentifier)})
 * - 두 캐시 모두 userIdentifier 기준이라 식별자 조회를 한 번만 수행하고 함께 무효화한다.
 *
 * [사용 시점]
 * - 사용자 닉네임 변경 트랜잭션이 성공한 뒤 호출한다.
 * - 트랜잭션 내부에서 호출하는 경우, DB 변경 롤백 가능성을 고려해 afterCommit에서 호출하는 편이 더 안전하다.
 *
 * [무효화 대상 식별자]
 * - 회원: ACTIVE user_sessions.session_id 기반 cache key
 * - 게스트: guest_sessions.guest_token 기반 cache key
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSenderProfileCacheEvictor {

    private final StringRedisTemplate redisTemplate;
    private final UserSessionRepository userSessionRepository;
    private final GuestSessionRepository guestSessionRepository;

    /**
     * userIdentifier에 해당하는 단일 채팅 발신자 프로필 캐시를 제거한다.
     *
     * @param userIdentifier WebSocket/Redis에서 사용하는 사용자 식별자
     */
    public void evictByUserIdentifier(String userIdentifier) {
        if (userIdentifier == null || userIdentifier.isBlank()) {
            return;
        }

        evictCacheKey(RedisKeys.chatSenderProfileKey(userIdentifier));
    }

    /**
     * 사용자 ID에 연결된 모든 사용자 표시 정보 캐시를 제거한다.
     *
     * 각 userIdentifier마다 채팅 발신자 프로필 캐시와 로비 닉네임 캐시 키를 함께 만들어
     * 한 번의 batch 삭제로 무효화한다.
     *
     * @param userId 사용자 ID
     */
    @Transactional(readOnly = true)
    public void evictByUserId(Long userId) {
        if (userId == null) {
            return;
        }

        List<String> userIdentifiers = findUserIdentifiers(userId);

        if (userIdentifiers.isEmpty()) {
            return;
        }

        List<String> cacheKeys = userIdentifiers.stream()
                .filter(userIdentifier -> userIdentifier != null && !userIdentifier.isBlank())
                .flatMap(userIdentifier -> Stream.of(
                        RedisKeys.chatSenderProfileKey(userIdentifier),
                        RedisKeys.userNicknameKey(userIdentifier)
                ))
                .toList();

        if (cacheKeys.isEmpty()) {
            return;
        }

        evictCacheKeys(userId, cacheKeys);
    }

    private List<String> findUserIdentifiers(Long userId) {
        List<String> userIdentifiers = new ArrayList<>();

        userIdentifiers.addAll(userSessionRepository.findSessionIdsByUserIdAndStatus(
                userId,
                UserSessionStatus.ACTIVE
        ));
        userIdentifiers.addAll(guestSessionRepository.findGuestTokensByUserId(userId));

        return userIdentifiers;
    }

    private void evictCacheKeys(Long userId, List<String> cacheKeys) {
        try {
            redisTemplate.delete(cacheKeys);
        } catch (RuntimeException e) {
            log.warn(
                    "사용자 표시 정보 캐시 일괄 무효화 실패. userId: {}, targetCount: {}",
                    userId,
                    cacheKeys.size(),
                    e
            );
        }
    }

    private void evictCacheKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn(
                    "채팅 발신자 프로필 캐시 무효화 실패. key: {}",
                    key,
                    e
            );
        }
    }
}