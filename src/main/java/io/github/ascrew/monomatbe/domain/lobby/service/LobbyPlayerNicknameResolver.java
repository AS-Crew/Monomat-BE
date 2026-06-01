package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * - 식별자 원문은 캐시 키(user:nickname:{userIdentifier})에만 사용되며 응답에 노출되지 않는다.
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

    /** TTL 프로퍼티가 비정상이거나 미주입된 경우 사용할 기본 캐시 TTL */
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final UserNicknameLookupService userNicknameLookupService;

    /**
     * 닉네임 캐시 TTL.
     *
     * Spring Context 밖에서 생성되는 단위 테스트에서는 @Value가 주입되지 않을 수 있으므로
     * effectiveCacheTtl()에서 null/zero/negative를 DEFAULT_CACHE_TTL로 보정한다.
     */
    @Value("${monomat.lobby.nickname-cache.ttl:PT10M}")
    private Duration cacheTtl;

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
     * 저장 실패는 목록 응답에 영향을 주지 않도록 무시하고 로그만 남긴다.
     */
    private void writeToCache(Map<String, String> loadedNicknames) {
        if (loadedNicknames.isEmpty()) {
            return;
        }

        Duration ttl = effectiveCacheTtl();

        loadedNicknames.forEach((userIdentifier, nickname) -> {
            if (nickname == null || nickname.isBlank()) {
                return;
            }

            try {
                redisTemplate.opsForValue().set(
                        RedisKeys.userNicknameKey(userIdentifier),
                        nickname,
                        ttl
                );
            } catch (RuntimeException e) {
                log.warn(
                        "닉네임 캐시 저장 실패 - 목록 응답은 계속 진행. userIdentifier: {}",
                        userIdentifier,
                        e
                );
            }
        });
    }

    private Duration effectiveCacheTtl() {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return DEFAULT_CACHE_TTL;
        }

        return cacheTtl;
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
