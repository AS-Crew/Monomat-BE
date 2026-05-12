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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원 인증정보 엔티티.
 *
 * users와 분리하여 관리하는 이유:
 * - 비밀번호 해시/로그인 실패 카운트/잠금 시각은 보안 민감 데이터
 * - 게스트 사용자에게는 불필요한 컬럼이므로 분리 시 모델이 단순해짐
 */
@Getter
@Entity
@Builder
@Table(name = "user_credentials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "failed_login_count", nullable = false)
    private Integer failedLoginCount;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        // 로그인 실패 카운트 초기값 보정
        if (this.failedLoginCount == null) {
            this.failedLoginCount = 0;
        }
    }

    @PreUpdate
    public void preUpdate() {
        // 인증정보 변경 시각 자동 갱신
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isLockedAt(LocalDateTime now) {
        return this.lockedUntil != null && this.lockedUntil.isAfter(now);
    }

    public void increaseFailedLoginCount() {
        if (this.failedLoginCount == null) {
            this.failedLoginCount = 0;
        }
        this.failedLoginCount += 1;
    }

    public void resetFailedLoginState() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void lockUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
