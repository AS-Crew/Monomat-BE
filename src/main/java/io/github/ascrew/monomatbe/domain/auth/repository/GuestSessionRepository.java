package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.GuestSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * guest_sessions 접근 리포지토리
 */
public interface GuestSessionRepository extends JpaRepository<GuestSession, Long> {

    /**
     * 게스트 UUID 토큰으로 세션 조회
     * 자동 로그인/세션 유효성 확인에 사용됩니다.
     */
    Optional<GuestSession> findByGuestToken(String guestToken);

    List<GuestSession> findByGuestTokenIn(Collection<String> guestTokens);

    /**
     * 게스트 userIdentifier에 대응되는 닉네임을 Projection으로 조회한다.
     *
     * [N+1 방지]
     * GuestSession.user는 LAZY 연관관계이므로 세션 엔티티 목록을 조회한 뒤
     * getUser().getUsername()을 호출하면 참여자 수만큼 추가 쿼리가 발생할 수 있다.
     * 따라서 join 쿼리로 userIdentifier와 nickname만 한 번에 조회한다.
     */
    @Query("""
            select g.guestToken as userIdentifier,
                   u.username as nickname
            from GuestSession g
            join g.user u
            where g.guestToken in :guestTokens
            """)
    List<UserIdentifierNicknameProjection> findNicknamesByGuestTokenIn(
            @Param("guestTokens") Collection<String> guestTokens
    );

    /**
     * 게스트 userIdentifier에 대응되는 사용자 프로필을 Projection으로 조회한다.
     *
     * [사용 목적]
     * 로비 채팅 메시지 신고를 위해 Redis 최근 채팅에 senderId와 senderNickname을 함께 저장한다.
     */
    @Query("""
            select g.guestToken as userIdentifier,
                   u.id as userId,
                   u.username as nickname
            from GuestSession g
            join g.user u
            where g.guestToken in :guestTokens
            """)
    List<UserIdentifierProfileProjection> findProfilesByGuestTokenIn(
            @Param("guestTokens") Collection<String> guestTokens
    );

    /**
     * 특정 게스트 사용자의 guestToken을 조회한다.
     *
     * [사용 목적]
     * 사용자의 닉네임이 변경된 경우, guestToken 기반 채팅 발신자 프로필 캐시를 제거하기 위해 사용한다.
     *
     * @param userId 사용자 ID
     * @return guestToken 목록
     */
    @Query("""
            select g.guestToken
            from GuestSession g
            where g.user.id = :userId
            """)
    List<String> findGuestTokensByUserId(@Param("userId") Long userId);
}