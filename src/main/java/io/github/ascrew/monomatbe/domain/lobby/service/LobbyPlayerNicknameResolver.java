package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.domain.lobby.config.LobbyNicknameCacheProperties;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 로비 참여자 userIdentifier를 사용자 닉네임으로 변환하는 컴포넌트
 *
 * [설계 이유]
 * LobbyQueryService는 로비 상세 응답 조립 책임만 가진다.
 * userIdentifier가 실제로 guest_sessions / user_sessions 중 어디에 저장되는지는
 * Auth 도메인의 내부 구현이므로 UserNicknameLookupService에 위임한다.
 *
 * [역할]
 * - userIdentifier -> nickname Map을 Cache -> DB 순으로 조회한다.
 * - 조회되지 않은 사용자를 위한 fallback nickname을 제공한다.
 *
 * [캐시 정책]
 * 로비 목록(GET /api/lobbies)은 호출 빈도가 높아 매 요청마다 닉네임을 DB로 조회하면
 * latency spike와 DB 커넥션 병목으로 증폭될 수 있다. 따라서 userIdentifier 기준 닉네임을
 * Redis에 짧은 TTL로 캐싱하고, cache miss인 식별자만 Auth 도메인 서비스로 조회한다.
 *
 * - 정상 조회된 닉네임만 캐싱한다. 세션 만료 등으로 닉네임이 없는 식별자는 negative 캐싱하지 않고,
 *   상위 계층에서 fallbackNickname()으로 처리한다.
 * - 캐싱으로 인해 닉네임 변경이 최대 TTL만큼 로비 목록/상세에 지연 반영될 수 있다.
 *   닉네임 변경 즉시성이 요구되지 않으므로 이를 수용한다.
 * - 캐시 키는 식별자 원문이 아니라 SHA-256 해시(user:nickname:{sha256(userIdentifier)})로 만들어,
 *   Redis 키/모니터링/SCAN 어디에도 세션·토큰 원문이 노출되지 않는다.
 *
 * [장애 정책]
 * 캐시 조회/저장 실패만으로 목록 API를 실패시키지 않는다.
 * 캐시 조회 실패 시 전부 miss로 간주해 DB 경로로 진행하고, 저장 실패는 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyPlayerNicknameResolver {

    private static final String UNKNOWN_NICKNAME_PREFIX = "Unknown-";

    /** fallback 표시값에 사용할 식별자 해시 앞자리 길이 (SHA-256 hex 기준) */
    private static final int HASH_SUFFIX_LENGTH = 6;

    private final StringRedisTemplate redisTemplate;
    private final UserNicknameLookupService userNicknameLookupService;
    private final LobbyNicknameCacheProperties nicknameCacheProperties;

    /**
     * 여러 userIdentifier에 대응되는 닉네임을 한 번에 조회한다.
     *
     * [조회 순서]
     * 1. Redis 캐시에서 batch(MGET)로 적중분을 먼저 조회한다.
     * 2. cache miss인 식별자만 Auth 도메인 서비스로 DB 조회한다.
     * 3. DB에서 새로 얻은 닉네임만 캐시에 저장한다.
     *
     * @param userIdentifiers 로비 참여자 userIdentifier 목록
     * @return userIdentifier -> nickname Map
     */
    public Map<String, String> resolveNicknameMap(Collection<String> userIdentifiers) {
        if (userIdentifiers == null || userIdentifiers.isEmpty()) {
            return Map.of();
        }

        List<String> distinctIdentifiers = userIdentifiers.stream()
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .distinct()
                .toList();

        if (distinctIdentifiers.isEmpty()) {
            return Map.of();
        }

        Map<String, String> nicknameMap = new HashMap<>();

        List<String> missedIdentifiers = readFromCache(distinctIdentifiers, nicknameMap);

        if (!missedIdentifiers.isEmpty()) {
            Map<String, String> loaded =
                    userNicknameLookupService.findNicknameMapByUserIdentifiers(missedIdentifiers);

            nicknameMap.putAll(loaded);
            writeToCache(loaded);
        }

        return nicknameMap;
    }

    /**
     * 캐시에서 닉네임 적중분을 조회해 resultMap에 채우고, cache miss인 식별자 목록을 반환한다.
     *
     * 캐시 조회가 실패하면 전부 miss로 간주해 입력 식별자를 그대로 반환한다.
     */
    private List<String> readFromCache(
            List<String> distinctIdentifiers,
            Map<String, String> resultMap
    ) {
        List<String> keys = distinctIdentifiers.stream()
                .map(RedisKeys::userNicknameKey)
                .toList();

        try {
            List<String> cachedNicknames = redisTemplate.opsForValue().multiGet(keys);

            if (cachedNicknames == null) {
                return distinctIdentifiers;
            }

            List<String> missedIdentifiers = new ArrayList<>();

            for (int i = 0; i < distinctIdentifiers.size(); i++) {
                String cached = i < cachedNicknames.size() ? cachedNicknames.get(i) : null;

                if (cached != null && !cached.isBlank()) {
                    resultMap.put(distinctIdentifiers.get(i), cached);
                } else {
                    missedIdentifiers.add(distinctIdentifiers.get(i));
                }
            }

            return missedIdentifiers;
        } catch (RuntimeException e) {
            log.warn(
                    "닉네임 캐시 조회 실패 - 전부 DB fallback 사용. targetCount: {}",
                    distinctIdentifiers.size(),
                    e
            );
            return distinctIdentifiers;
        }
    }

    /**
     * DB에서 새로 조회된 닉네임만 캐시에 저장한다.
     *
     * [batch 저장]
     * 식별자 수만큼 개별 SET을 날리면 서버↔Redis RTT가 누적되어 캐시가 또 다른 병목이 된다.
     * 따라서 executePipelined로 SET(+TTL)을 한 번에 묶어 round-trip을 줄인다.
     *
     * [장애 정책]
     * 저장 실패는 목록 응답에 영향을 주지 않도록 무시하고 로그만 남긴다.
     * 로그에는 식별자 원문 대신 대상 개수만 남겨 식별자 노출을 막는다.
     */
    private void writeToCache(Map<String, String> loadedNicknames) {
        if (loadedNicknames.isEmpty()) {
            return;
        }

        Duration ttl = nicknameCacheProperties.getTtl();

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                org.springframework.data.redis.connection.StringRedisConnection stringConnection =
                        connection instanceof org.springframework.data.redis.connection.StringRedisConnection ?
                                (org.springframework.data.redis.connection.StringRedisConnection) connection :
                                new org.springframework.data.redis.connection.DefaultStringRedisConnection(connection);
                loadedNicknames.forEach((userIdentifier, nickname) -> {
                    if (nickname == null || nickname.isBlank()) {
                        return;
                    }
                    stringConnection.set(
                            RedisKeys.userNicknameKey(userIdentifier),
                            nickname,
                            Expiration.from(ttl),
                            org.springframework.data.redis.connection.RedisStringCommands.SetOption.upsert()
                    );
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.warn(
                    "닉네임 캐시 batch 저장 실패 - 목록 응답은 계속 진행. targetCount: {}",
                    loadedNicknames.size(),
                    e
            );
        }
    }

    /**
     * 닉네임이 없는 식별자에 대한 fallback 값을 반환한다.
     *
     * [fallback 정책]
     * Redis participants에는 남아 있지만 DB 세션이 이미 정리된 경우,
     * 상세 조회 전체를 실패시키면 대기실 UI가 깨진다.
     * 따라서 닉네임 대신 안전한 표시값을 내려준다.
     *
     * [보안 — 식별자 원문 미노출]
     * userIdentifier는 세션 ID 또는 게스트 토큰이므로 원문(일부 포함)을 응답에 노출하면 안 된다.
     * 특히 로비 목록은 호출 빈도가 높고 노출 범위가 넓다.
     * 따라서 식별자의 SHA-256 해시 앞 6자리(비가역)만 사용해 표시값을 만든다.
     * 결정적이므로 같은 식별자는 항상 같은 fallback 값으로 표시된다.
     */
    public String fallbackNickname(String userIdentifier) {
        if (userIdentifier == null || userIdentifier.isBlank()) {
            return UNKNOWN_NICKNAME_PREFIX + "user";
        }

        String hashSuffix = TokenHashUtils.sha256(userIdentifier)
                .substring(0, HASH_SUFFIX_LENGTH);

        return UNKNOWN_NICKNAME_PREFIX + hashSuffix;
    }
}
