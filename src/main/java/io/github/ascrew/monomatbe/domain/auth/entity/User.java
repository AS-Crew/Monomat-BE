package io.github.ascrew.monomatbe.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 사용자 기본 엔티티
 *
 * 게스트/회원을 하나의 users 테이블로 통합 관리합니다.
 * 인증 방식(게스트/회원)은 userType으로 구분하고,
 * 서비스 인가 권한은 role로 구분하며,
 * 공통 프로필 정보(닉네임, 상태, 생성일 등)는 이 엔티티에서 관리합니다.
 */
@Getter
@Entity
@Builder
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class User {

    private static final int USERNAME_MAX_LENGTH = 50;
    private static final int USER_TYPE_MAX_LENGTH = 20;
    private static final int USER_STATUS_MAX_LENGTH = 20;
    private static final int USER_ROLE_MAX_LENGTH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = USERNAME_MAX_LENGTH)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = USER_TYPE_MAX_LENGTH)
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = USER_STATUS_MAX_LENGTH)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = USER_ROLE_MAX_LENGTH)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;

        if (this.status == null) {
            this.status = UserStatus.ACTIVE;
        }
        if (this.userType == null) {
            this.userType = UserType.GUEST;
        }
        if (this.role == null) {
            this.role = UserRole.USER;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 정식 회원 닉네임 변경 시 사용합니다.
     *
     * @param username 변경할 닉네임
     */
    public void updateUsername(String username) {
        this.username = username;
    }

    /**
     * 로그인 성공 시 마지막 로그인 시각을 갱신할 때 사용합니다.
     */
    public void updateLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}