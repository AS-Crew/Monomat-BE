package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserIdentifierNicknameProjection;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * userIdentifier 기준 사용자 닉네임 조회를 담당하는 Auth 도메인 서비스
 *
 * [설계 이유]
 * userIdentifier는 JWT, WebSocket, Redis 로비 상태에서 공통으로 사용하는 사용자 식별자다.
 * 하지만 이 식별자가 guest_sessions.guest_token 또는 user_sessions.session_id에 저장된다는 사실은
 * Auth 도메인의 내부 저장 구조다.
 *
 * 따라서 Lobby 도메인이 GuestSessionRepository / UserSessionRepository를 직접 참조하지 않도록
 * 닉네임 조회 책임을 Auth 도메인 서비스로 모은다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNicknameLookupService {

    private final GuestSessionRepository guestSessionRepository;
    private final UserSessionRepository userSessionRepository;

    /**
     * 여러 userIdentifier에 대응되는 닉네임을 한 번에 조회한다.
     *
     * [조회 정책]
     * - 게스트: guest_sessions.guest_token 기준 조회
     * - 회원: user_sessions.session_id 기준 조회
     * - 두 조회 결과를 userIdentifier -> nickname Map으로 병합한다.
     *
     * [장애 처리]
     * 대기실 상세 조회는 UI 표시 목적이므로, 한쪽 조회가 실패해도 예외를 전파하지 않는다.
     * 가능한 조회 결과만 반환하고, 누락된 사용자는 상위 계층에서 fallback nickname을 사용한다.
     *
     * @param userIdentifiers 사용자 식별자 목록
     * @return userIdentifier -> nickname Map
     */
    public Map<String, String> findNicknameMapByUserIdentifiers(Collection<String> userIdentifiers) {
        if (userIdentifiers == null || userIdentifiers.isEmpty()) {
            return Map.of();
        }

        Map<String, String> nicknameMap = new HashMap<>();

        resolveGuestNicknames(userIdentifiers, nicknameMap);
        resolveRegisteredUserNicknames(userIdentifiers, nicknameMap);

        return nicknameMap;
    }

    private void resolveGuestNicknames(
            Collection<String> userIdentifiers,
            Map<String, String> nicknameMap
    ) {
        try {
            for (UserIdentifierNicknameProjection projection :
                    guestSessionRepository.findNicknamesByGuestTokenIn(userIdentifiers)) {
                putIfValid(nicknameMap, projection);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "게스트 닉네임 조회 실패 - fallback nickname 사용. targetCount: {}",
                    userIdentifiers.size(),
                    e
            );
        }
    }

    private void resolveRegisteredUserNicknames(
            Collection<String> userIdentifiers,
            Map<String, String> nicknameMap
    ) {
        try {
            for (UserIdentifierNicknameProjection projection :
                    userSessionRepository.findNicknamesBySessionIdIn(userIdentifiers)) {
                putIfValid(nicknameMap, projection);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "회원 닉네임 조회 실패 - fallback nickname 사용. targetCount: {}",
                    userIdentifiers.size(),
                    e
            );
        }
    }

    private void putIfValid(
            Map<String, String> nicknameMap,
            UserIdentifierNicknameProjection projection
    ) {
        if (projection == null
                || projection.getUserIdentifier() == null
                || projection.getUserIdentifier().isBlank()
                || projection.getNickname() == null
                || projection.getNickname().isBlank()) {
            return;
        }

        nicknameMap.put(
                projection.getUserIdentifier(),
                projection.getNickname()
        );
    }
}