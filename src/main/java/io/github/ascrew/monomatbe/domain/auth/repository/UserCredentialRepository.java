package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * user_credentials 접근 리포지토리
 */
public interface UserCredentialRepository extends JpaRepository<UserCredential, Long> {

    boolean existsByLoginId(String loginId);

    /**
     * 로그인 ID로 인증정보 조회
     * 회원 로그인 시 패스워드 검증 진입점이 된다.
     */
    Optional<UserCredential> findByLoginId(String loginId);

    /**
     * 사용자 ID로 인증정보를 조회한다.
     *
     * [사용처]
     * - 현재 로그인한 정식 회원의 비밀번호 변경
     *
     * [주의]
     * 게스트 사용자는 user_credentials row가 없으므로 Optional.empty()가 정상 케이스다.
     */
    Optional<UserCredential> findByUser_Id(Long userId);
}