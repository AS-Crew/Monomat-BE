package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * user_credentials 접근 리포지토리.
 */
public interface UserCredentialRepository extends JpaRepository<UserCredential, Long> {

    /**
     * 로그인 ID로 인증정보 조회.
     * 회원 로그인 시 패스워드 검증 진입점이 됩니다.
     */
    Optional<UserCredential> findByLoginId(String loginId);
}
