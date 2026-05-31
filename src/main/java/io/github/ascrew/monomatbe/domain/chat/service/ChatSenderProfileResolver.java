package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserIdentifierProfile;
import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 채팅 발신자 프로필 Resolver
 *
 * [책임]
 * - 채팅 메시지 생성에 필요한 senderId / senderNickname을 userIdentifier 기준으로 조회한다.
 * - 채팅 전송 hot path에서 매번 DB 조회가 발생하지 않도록 Redis cache를 먼저 확인한다.
 * - cache miss일 때만 Auth 도메인의 UserNicknameLookupService를 통해 프로필을 조회한다.
 *
 * [장애 정책]
 * 채팅 전송은 실시간성이 중요하므로, 프로필 캐시/조회 실패만으로 메시지 전송을 막지 않는다.
 * 조회 실패 시 userIdentifier만 포함하고 senderId / nickname은 null로 둔다.
 *
 * [캐시 정책]
 * - 정상 조회된 resolved profile만 Redis에 캐싱한다.
 * - 조회 실패 또는 존재하지 않는 userIdentifier로 생성된 unresolved profile은 캐싱하지 않는다.
 * - unresolved profile은 채팅 전송 fallback 용도로만 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSenderProfileResolver {

    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    @Qualifier("cacheJsonMapper")
    private final JsonMapper jsonMapper;

    private final UserNicknameLookupService userNicknameLookupService;

    @Value("${monomat.chat.sender-profile-cache.ttl:PT30M}")
    private Duration cacheTtl;

    /**
     * userIdentifier 기준 채팅 발신자 프로필을 조회한다.
     *
     * @param userIdentifier 사용자 식별자
     * @return 채팅 발신자 프로필
     */
    public ChatSenderProfile resolve(String userIdentifier) {
        if (userIdentifier == null || userIdentifier.isBlank()) {
            return ChatSenderProfile.unresolved(userIdentifier);
        }

        return findFromCache(userIdentifier);
    }

    private ChatSenderProfile findFromCache(String userIdentifier) {
        String key = RedisKeys.chatSenderProfileKey(userIdentifier);

        try {
            String cached = redisTemplate.opsForValue().get(key);

            if (cached != null && !cached.isBlank()) {
                return deserialize(cached);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "채팅 발신자 프로필 캐시 조회 실패 - DB fallback 사용. userIdentifier: {}",
                    userIdentifier,
                    e
            );
        }

        ChatSenderProfile loaded = loadProfile(userIdentifier);
        cacheResolvedProfile(key, loaded);

        return loaded;
    }

    private ChatSenderProfile loadProfile(String userIdentifier) {
        try {
            Map<String, UserIdentifierProfile> profileMap =
                    userNicknameLookupService.findProfileMapByUserIdentifiers(List.of(userIdentifier));

            UserIdentifierProfile profile = profileMap.get(userIdentifier);

            if (profile == null) {
                return ChatSenderProfile.unresolved(userIdentifier);
            }

            return ChatSenderProfile.builder()
                    .userIdentifier(userIdentifier)
                    .userId(profile.userId())
                    .nickname(profile.nickname())
                    .build();
        } catch (RuntimeException e) {
            log.warn(
                    "채팅 발신자 프로필 조회 실패 - unresolved profile 사용. userIdentifier: {}",
                    userIdentifier,
                    e
            );

            return ChatSenderProfile.unresolved(userIdentifier);
        }
    }

    private void cacheResolvedProfile(String key, ChatSenderProfile profile) {
        if (!profile.isResolved()) {
            return;
        }

        cacheProfile(key, profile);
    }

    private void cacheProfile(String key, ChatSenderProfile profile) {
        try {
            redisTemplate.opsForValue().set(
                    key,
                    serialize(profile),
                    effectiveCacheTtl()
            );
        } catch (RuntimeException e) {
            log.warn(
                    "채팅 발신자 프로필 캐시 저장 실패 - 채팅 전송은 계속 진행. key: {}",
                    key,
                    e
            );
        }
    }

    private Duration effectiveCacheTtl() {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return DEFAULT_CACHE_TTL;
        }

        return cacheTtl;
    }

    private String serialize(ChatSenderProfile profile) {
        try {
            return jsonMapper.writeValueAsString(profile);
        } catch (JacksonException e) {
            throw new ChatSenderProfileSerializationException(
                    "채팅 발신자 프로필 직렬화에 실패했습니다.",
                    e
            );
        }
    }

    private ChatSenderProfile deserialize(String payload) {
        try {
            return jsonMapper.readValue(payload, ChatSenderProfile.class);
        } catch (JacksonException e) {
            throw new ChatSenderProfileSerializationException(
                    "채팅 발신자 프로필 역직렬화에 실패했습니다.",
                    e
            );
        }
    }

    private static class ChatSenderProfileSerializationException extends RuntimeException {

        private ChatSenderProfileSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}