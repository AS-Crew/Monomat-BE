package io.github.ascrew.monomatbe.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관리자 권한 엔티티
 *
 * [책임]
 * - 현재 프로젝트에 ROLE_ADMIN 권한 체계가 없으므로, 관리자 API 접근 허용 대상을 별도 테이블로 관리한다.
 * - admin_users.user_id에 등록된 users.id만 관리자 API에 접근할 수 있다.
 *
 * [설계 의도]
 * - users 테이블에 role 컬럼을 바로 추가하지 않고, 기존 인증 모델 영향 없이 관리자 권한을 분리한다.
 * - 운영 중 DB insert/delete만으로 관리자 접근 권한을 변경할 수 있도록 한다.
 *
 * [확장 방향]
 * - 추후 ROLE_ADMIN 또는 user_roles 테이블을 도입하면 이 엔티티는 마이그레이션 대상이 된다.
 */
@Getter
@Entity
@Table(
        name = "admin_users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_admin_users_user_id",
                        columnNames = "user_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 관리자 권한을 가진 사용자
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private AdminUser(User user) {
        this.user = user;
    }

    public static AdminUser create(User user) {
        return new AdminUser(user);
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}