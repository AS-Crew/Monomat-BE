package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
     * 초대 코드 존재 여부를 확인한다.
     * 로비 생성 시 invite_code 중복 체크에 사용된다.
     * Redis SETNX와 이중 방어선으로 동작한다.
     */
    boolean existsByInviteCode(String inviteCode);

    /**
     * status 조건부 map_id 갱신.
     *
     * 맵 변경 시 status == WAITING인 경우에만 갱신하여 동시 게임 시작 레이스를 방지한다.
     * 반환값이 0이면 이미 PLAYING이거나 초대 코드가 없는 경우다.
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
}