package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * users 테이블 접근 리포지토리.
 *
 * 게스트 닉네임 정책에서
 * - 전체 중복 체크
 * - REGISTERED 선점 닉네임 체크
 * 를 빠르게 처리하기 위해 exists 쿼리를 사용합니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 게스트/회원 구분 없이 닉네임 존재 여부 확인.
     */
    boolean existsByUsername(String username);

    /**
     * 특정 사용자 유형에서 닉네임 존재 여부 확인.
     * (예: REGISTERED 닉네임 선점 검사)
     */
    boolean existsByUsernameAndUserType(String username, UserType userType);

    /**
     * 닉네임으로 사용자 단건 조회.
     */
    Optional<User> findByUsername(String username);
}
