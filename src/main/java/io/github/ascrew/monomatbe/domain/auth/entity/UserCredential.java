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
 * 회원 인증정보 엔티티
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

        if (this.failedLoginCount == null) {
            this.failedLoginCount = 0;
        }
    }

    @PreUpdate
    public void preUpdate() {
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

    /**
     * 비밀번호를 새 해시로 변경하고 변경 시각을 갱신한다.
     *
     * [정책]
     * - raw password는 엔티티로 전달하지 않는다.
     * - 해시는 서비스 계층에서 PasswordEncoder로 생성한 뒤 전달한다.
     * - 현재 비밀번호 검증을 통과한 경우에만 호출된다.
     * - 재인증에 성공한 사용자의 비밀번호 변경이므로 기존 로그인 실패 횟수와 잠금 상태를 초기화한다.
     *
     * @param passwordHash 새 비밀번호 해시
     * @param changedAt 비밀번호 변경 시각
     */
    public void changePassword(String passwordHash, LocalDateTime changedAt) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = changedAt;
        this.updatedAt = changedAt;
        resetFailedLoginState();
    }
}