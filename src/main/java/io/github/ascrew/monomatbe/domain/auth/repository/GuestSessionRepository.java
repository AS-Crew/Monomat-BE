package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.GuestSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * guest_sessions 접근 리포지토리.
 */
public interface GuestSessionRepository extends JpaRepository<GuestSession, Long> {

    /**
     * 게스트 UUID 토큰으로 세션 조회.
     * 자동 로그인/세션 유효성 확인에 사용됩니다.
     */
    Optional<GuestSession> findByGuestToken(String guestToken);

    java.util.List<GuestSession> findByGuestTokenIn(java.util.Collection<String> guestTokens);
}
