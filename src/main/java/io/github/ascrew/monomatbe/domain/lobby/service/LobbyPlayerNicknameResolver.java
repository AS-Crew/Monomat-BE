package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.GuestSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 로비 참여자 userIdentifier를 사용자 닉네임으로 변환하는 컴포넌트
 *
 * [설계 이유]
 * LobbyQueryService가 게스트/회원 세션 저장 구조를 직접 알게 되면 로비 조회 책임과 인증 세션 조회 책임이 섞인다.
 * 따라서 userIdentifier -> nickname 변환 책임을 이 컴포넌트로 분리한다.
 *
 * [조회 정책]
 * - 게스트: guest_sessions.guest_token 기준 조회
 * - 회원: user_sessions.session_id 기준 조회
 * - 두 경로를 모두 조회한 뒤 userIdentifier 기준으로 nickname Map을 만든다.
 * - 알 수 없는 userIdentifier는 null로 두지 않고 fallback 표시값을 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyPlayerNicknameResolver {

    private static final String UNKNOWN_NICKNAME_PREFIX = "Unknown-";

    private final GuestSessionRepository guestSessionRepository;
    private final UserSessionRepository userSessionRepository;

    /**
     * 여러 userIdentifier에 대응되는 닉네임을 한 번에 조회한다.
     *
     * [N+1 방지]
     * participants 수만큼 DB를 반복 조회하지 않고,
     * guest_sessions / user_sessions를 각각 IN query로 조회한다.
     *
     * @param userIdentifiers 로비 참여자 userIdentifier 목록
     * @return userIdentifier -> nickname Map
     */
    public Map<String, String> resolveNicknameMap(Collection<String> userIdentifiers) {
        if (userIdentifiers == null || userIdentifiers.isEmpty()) {
            return Map.of();
        }

        Map<String, String> nicknameMap = new HashMap<>();

        resolveGuestNicknames(userIdentifiers, nicknameMap);
        resolveRegisteredUserNicknames(userIdentifiers, nicknameMap);

        return nicknameMap;
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

    private void resolveGuestNicknames(
            Collection<String> userIdentifiers,
            Map<String, String> nicknameMap
    ) {
        try {
            for (GuestSession guestSession : guestSessionRepository.findByGuestTokenIn(userIdentifiers)) {
                if (guestSession.getUser() == null) {
                    continue;
                }

                nicknameMap.put(
                        guestSession.getGuestToken(),
                        guestSession.getUser().getUsername()
                );
            }
        } catch (RuntimeException e) {
            log.warn("로비 참여자 게스트 닉네임 조회 실패 - fallback nickname 사용", e);
        }
    }

    private void resolveRegisteredUserNicknames(
            Collection<String> userIdentifiers,
            Map<String, String> nicknameMap
    ) {
        try {
            for (UserSession userSession : userSessionRepository.findBySessionIdIn(userIdentifiers)) {
                if (userSession.getUser() == null) {
                    continue;
                }

                nicknameMap.put(
                        userSession.getSessionId(),
                        userSession.getUser().getUsername()
                );
            }
        } catch (RuntimeException e) {
            log.warn("로비 참여자 회원 닉네임 조회 실패 - fallback nickname 사용", e);
        }
    }
}