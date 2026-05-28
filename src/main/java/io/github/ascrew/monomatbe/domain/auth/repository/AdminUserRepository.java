package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.AdminUser;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    boolean existsByUserId(Long userId);

    boolean existsByUserIdAndUserUserType(Long userId, UserType userType);
}