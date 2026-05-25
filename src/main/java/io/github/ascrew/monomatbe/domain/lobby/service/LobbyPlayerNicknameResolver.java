package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
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
     * 따라서 식별자 일부를 포함한 안전한 표시값으로 내려준다.
     */
    public String fallbackNickname(String userIdentifier) {
        if (userIdentifier == null || userIdentifier.isBlank()) {
            return UNKNOWN_NICKNAME_PREFIX + "user";
        }

        String compact = userIdentifier.replace("-", "");
        String suffix = compact.length() <= 6
                ? compact
                : compact.substring(0, 6);

        return UNKNOWN_NICKNAME_PREFIX + suffix;
    }
}