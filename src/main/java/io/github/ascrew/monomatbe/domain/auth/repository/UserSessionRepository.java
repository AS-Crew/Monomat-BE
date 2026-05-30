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
 * user_sessions 접근 리포지토리
 *
 * JWT 단독 전략이더라도, 강제 로그아웃/세션 추적 요구가 생기면 서버 세션 저장소로 확장할 수 있습니다.
 */
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * 세션 토큰으로 세션 조회
     */
    Optional<UserSession> findBySessionToken(String sessionToken);

    Optional<UserSession> findBySessionId(String sessionId);

    List<UserSession> findBySessionIdIn(Collection<String> sessionIds);

    /**
     * 회원 userIdentifier에 대응되는 닉네임을 Projection으로 조회한다.
     *
     * [N+1 방지]
     * UserSession.user는 LAZY 연관관계이므로 세션 엔티티 목록을 조회한 뒤
     * getUser().getUsername()을 호출하면 참여자 수만큼 추가 쿼리가 발생할 수 있다.
     * 따라서 join 쿼리로 userIdentifier와 nickname만 한 번에 조회한다.
     */
    @Query("""
            select s.sessionId as userIdentifier,
                   u.username as nickname
            from UserSession s
            join s.user u
            where s.sessionId in :sessionIds
            """)
    List<UserIdentifierNicknameProjection> findNicknamesBySessionIdIn(
            @Param("sessionIds") Collection<String> sessionIds
    );

    /**
     * 회원 userIdentifier에 대응되는 사용자 프로필을 Projection으로 조회한다.
     *
     * [사용 목적]
     * 로비 채팅 메시지 신고를 위해 Redis 최근 채팅에 senderId와 senderNickname을 함께 저장한다.
     */
    @Query("""
            select s.sessionId as userIdentifier,
                   u.id as userId,
                   u.username as nickname
            from UserSession s
            join s.user u
            where s.sessionId in :sessionIds
            """)
    List<UserIdentifierProfileProjection> findProfilesBySessionIdIn(
            @Param("sessionIds") Collection<String> sessionIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSession s where s.sessionId = :sessionId")
    Optional<UserSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    Optional<UserSession> findBySessionIdAndStatus(String sessionId, UserSessionStatus status);

    List<UserSession> findByUser_IdAndStatusOrderByCreatedAtAsc(Long userId, UserSessionStatus status);

    List<UserSession> findByStatusAndExpiresAtBefore(UserSessionStatus status, LocalDateTime now);

    List<UserSession> findByStatusInAndUpdatedAtBefore(Collection<UserSessionStatus> statuses, LocalDateTime threshold);
}