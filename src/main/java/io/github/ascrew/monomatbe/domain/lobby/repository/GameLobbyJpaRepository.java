package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import org.springframework.data.jpa.repository.JpaRepository;

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
}