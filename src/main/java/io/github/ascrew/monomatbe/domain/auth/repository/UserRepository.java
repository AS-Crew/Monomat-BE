package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 사용자 row를 PESSIMISTIC_WRITE로 잠근 채 조회한다.
     *
     * [사용처]
     * - 회원 로그인 시 계정 단위로 중복 로그인 판정~신규 세션 저장 구간을 직렬화하기 위해 사용한다. (#204)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
