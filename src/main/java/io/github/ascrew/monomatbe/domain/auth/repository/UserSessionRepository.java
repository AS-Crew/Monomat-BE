package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * user_sessions 접근 리포지토리.
 *
 * JWT 단독 전략이더라도,
 * 강제 로그아웃/세션 추적 요구가 생기면 서버 세션 저장소로 확장할 수 있습니다.
 */
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * 세션 토큰으로 세션 조회.
     */
    Optional<UserSession> findBySessionToken(String sessionToken);
}
