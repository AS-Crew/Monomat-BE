package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    Optional<UserSession> findBySessionId(String sessionId);

    List<UserSession> findBySessionIdIn(Collection<String> sessionIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSession s where s.sessionId = :sessionId")
    Optional<UserSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    Optional<UserSession> findBySessionIdAndStatus(String sessionId, UserSessionStatus status);

    List<UserSession> findByUser_IdAndStatusOrderByCreatedAtAsc(Long userId, UserSessionStatus status);

    List<UserSession> findByStatusAndExpiresAtBefore(UserSessionStatus status, LocalDateTime now);

    List<UserSession> findByStatusInAndUpdatedAtBefore(Collection<UserSessionStatus> statuses, LocalDateTime threshold);
}
