package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 관리자 사용자 Repository
 *
 * [책임]
 * - users.id 기준 관리자 여부 확인
 *
 * [주의]
 * - 현재는 관리자 API 접근 제어 용도로만 사용한다.
 * - 관리자 권한 부여/회수 API는 아직 제공하지 않는다.
 */
public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    boolean existsByUserId(Long userId);
}