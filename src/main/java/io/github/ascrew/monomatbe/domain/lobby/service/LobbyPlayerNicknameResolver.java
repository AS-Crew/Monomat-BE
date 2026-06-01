package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
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
 * - Auth 도메인 서비스에서 userIdentifier -> nickname Map을 조회한다.
 * - 조회되지 않은 사용자를 위한 fallback nickname을 제공한다.
 */
@Component
@RequiredArgsConstructor
public class LobbyPlayerNicknameResolver {

    private static final String UNKNOWN_NICKNAME_PREFIX = "Unknown-";

    /** fallback 표시값에 사용할 식별자 해시 앞자리 길이 (SHA-256 hex 기준) */
    private static final int HASH_SUFFIX_LENGTH = 6;

    private final UserNicknameLookupService userNicknameLookupService;

    /**
     * 여러 userIdentifier에 대응되는 닉네임을 한 번에 조회한다.
     *
     * @param userIdentifiers 로비 참여자 userIdentifier 목록
     * @return userIdentifier -> nickname Map
     */
    public Map<String, String> resolveNicknameMap(Collection<String> userIdentifiers) {
        return userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);
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