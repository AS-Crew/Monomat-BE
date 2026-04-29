package io.github.ascrew.monomatbe.repository;

import io.github.ascrew.monomatbe.domain.GameLobby;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// GAME_LOBBY 테이블에 대한 JPA 데이터 접근 인터페이스

// [역할 구분]
// - GameLobbyJpaRepository : MySQL (DB) 영속석 담당
// - LobbyRespository : Redis 인메모리 데이터 담당
// 두 인터페이스는 역할이 완전히 다르니까 혼용하지 않도록 주의

// 파일명에 Jpa를 붙여 같은 패키지의 LobbyRepository (Redis)와 구분하기

public interface GameLobbyJpaRepository extends JpaRepository<GameLobby, Long> {

    // invite_code 기준으로 로비 존재 여부 확인

    // [사용 목적]
    // 로비 생성 서비스에서 6자리 코드 중복 체크 루프에 사용됨
    // Spring Data JPA가 SELECT COUNT(1) > 0 쿼리를 자동 생성하므로 SELECT *보다 가볍게 동작함

    boolean existsByInviteCode(String inviteCode);

    // invite_code로 로비 조회

    // [사용 목적]
    // 유저가 초대 코드를 직접 입력하거나 딥링크 (/lobby/{code})로 접근할 때, 해당 로비 정보를 DB에서 가져오기 위해 사용함

    // [Optional 반환 이유]
    // 존재하지 않는 코드로 조회 시 NullPointerException 대신
    // Optional.empty()를 반환하여 호출부에서 안전하게 처리하도록 강제함
    Optional<GameLobby> findByInviteCode(String inviteCode);
}