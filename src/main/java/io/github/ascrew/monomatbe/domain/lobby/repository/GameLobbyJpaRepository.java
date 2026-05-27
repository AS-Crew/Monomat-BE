package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * GAME_LOBBY 테이블 접근 JPA 리포지토리.
 *
 * [LobbyRepository(Redis)와 분리한 이유]
 * LobbyRepository는 실시간 Redis 데이터를 다루는 인터페이스
 * DB 영속성은 JPA 리포지토리로 분리하여 각 저장소의 책임을 명확히 한다.
 *
 * [네이밍 규칙]
 * Redis 기반 LobbyRepository와 구분하기 위해 GameLobbyJpaRepository로 명명함
 */
// JpaRepository<엔티티 클래스, 엔티티의 PK 타입>을 상속받아 기본적인 CRUD 메서드를 자동으로 제공받는다.
public interface GameLobbyJpaRepository extends JpaRepository<GameLobby, Long> {

    /**
     * 초대 코드로 로비를 조회
     * 로비 입장, 신고 등 invite_code 기반 조회에 사용된다.
     */
    Optional<GameLobby> findByInviteCode(String inviteCode);

    /**
     * 초대 코드로 로비를 조회하면서 행에 대한 PESSIMISTIC_WRITE 락을 획득한다.
     *
     * [필요 이유]
     * 게임 시작(startLobbyGame)과 맵 변경(updateMap)이 동일 로비 row를 동시에 변경하면
     * Lost Update가 발생할 수 있다.
     *   - 게임 시작은 entity의 status를 PLAYING으로 변경한 뒤 saveAndFlush로 행 전체를 UPDATE한다.
     *   - 그 사이 맵 변경이 conditional update로 mapId를 바꿔도, 게임 시작의 saveAndFlush가
     *     T1 시점의 mapId로 덮어쓰면 검증한 맵과 실제 시작 맵이 어긋난다.
     * 두 트랜잭션 모두 이 메서드로 행 락을 획득하여 직렬화한다.
     *
     * [Lock timeout 정책]
     * jakarta.persistence.lock.timeout = 3000 ms.
     * Hibernate가 MySQL InnoDB의 innodb_lock_wait_timeout 세션 변수에 반영한다.
     * 타임아웃 초과 시 PessimisticLockingFailureException(LockTimeoutException 포함)이 던져진다.
     * 호출자(LobbyMapUpdateService / LobbyStartService)는 이를 잡아 409 CONFLICT로 변환한다.
     *
     * [참조 패턴]
     * UserSessionRepository.findBySessionIdForUpdate 와 동일한 PESSIMISTIC_WRITE 패턴이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT g FROM GameLobby g WHERE g.inviteCode = :code")
    Optional<GameLobby> findByInviteCodeForUpdate(@Param("code") String code);

    /**
     * 초대 코드 존재 여부를 확인한다.
     * 로비 생성 시 invite_code 중복 체크에 사용된다.
     * Redis SETNX와 이중 방어선으로 동작한다.
     */
    boolean existsByInviteCode(String inviteCode);

    /**
     * status가 지정한 값인 경우에만 map_id를 갱신한다.
     *
     * [정책]
     * 맵 변경 시 status == WAITING 원자 검증으로 동시 게임 시작 레이스를 방지한다.
     * status 값은 호출자가 LobbyStatus.WAITING을 전달한다.
     * JPQL 문자열에 enum 풀네임을 박지 않기 위해 파라미터로 받는다 (패키지 리네임 안전).
     *
     * [0행 반환 의미]
     * 반환값이 0이면 다음 두 경우 중 하나다.
     *   1) 해당 초대 코드의 row가 더 이상 존재하지 않음 (DB 스냅샷 누락)
     *   2) row는 있지만 status가 전달한 값과 다름 (이미 PLAYING으로 전환됨)
     * 호출자는 existsByInviteCode 등으로 두 경우를 구분하여 분기 처리해야 한다.
     *
     * @param code   로비 초대 코드
     * @param mapId  새 맵 ID (null이면 맵 미선택 상태로 복원)
     * @param status 갱신을 허용할 현재 상태 (호출 측에서 LobbyStatus.WAITING 전달)
     * @return 갱신된 행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE GameLobby g SET g.mapId = :mapId WHERE g.inviteCode = :code AND g.status = :status")
    int updateMapIdIfWaiting(
            @Param("code") String code,
            @Param("mapId") Long mapId,
            @Param("status") LobbyStatus status);

    /**
     * status가 지정한 값인 경우에만 map_id와 question_count를 함께 갱신한다.
     *
     * 맵 변경 시 새 맵의 numOfSong으로 questionCount를 재설정할 때 사용한다.
     *
     * @param code          로비 초대 코드
     * @param mapId         새 맵 ID (null이면 맵 미선택 상태로 복원)
     * @param questionCount 새 문제 갯수 (새 맵의 numOfSong)
     * @param status        갱신을 허용할 현재 상태 (호출 측에서 LobbyStatus.WAITING 전달)
     * @return 갱신된 행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE GameLobby g SET g.mapId = :mapId, g.questionCount = :questionCount WHERE g.inviteCode = :code AND g.status = :status")
    int updateMapAndQuestionCountIfWaiting(
            @Param("code") String code,
            @Param("mapId") Long mapId,
            @Param("questionCount") Integer questionCount,
            @Param("status") LobbyStatus status);
}
