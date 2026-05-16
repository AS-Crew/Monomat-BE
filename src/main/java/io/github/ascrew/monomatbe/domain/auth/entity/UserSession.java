package io.github.ascrew.monomatbe.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * 회원 세션 엔티티(확장 대비).
 *
 * 현재 게스트 로그인은 guest_sessions + Redis를 사용하지만,
 * 회원 로그인 이슈(#35)에서 서버 세션 추적이 필요할 수 있어
 * user_sessions 테이블 매핑을 미리 준비해 둔 상태입니다.
 */
@Getter
@Entity
@Builder
@Table(name = "user_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "session_id", nullable = false, unique = true, length = 100)
    private String sessionId;

    @Column(name = "session_token", nullable = false, unique = true, length = 255)
    private String sessionToken;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserSessionStatus status;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = UserSessionStatus.ACTIVE;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActiveAt(LocalDateTime now) {
        return status == UserSessionStatus.ACTIVE && expiresAt.isAfter(now);
    }

    public void rotate(String newSessionToken, LocalDateTime newExpiresAt, LocalDateTime now, String ipAddress, String userAgent) {
        this.sessionToken = newSessionToken;
        this.expiresAt = newExpiresAt;
        this.status = UserSessionStatus.ACTIVE;
        this.revokedAt = null;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.updatedAt = now;
    }

    public void markLogout(LocalDateTime now) {
        this.status = UserSessionStatus.LOGOUT;
        this.revokedAt = now;
        this.updatedAt = now;
    }

    public void markRevoked(LocalDateTime now) {
        this.status = UserSessionStatus.REVOKED;
        this.revokedAt = now;
        this.updatedAt = now;
    }

    public void markExpired(LocalDateTime now) {
        this.status = UserSessionStatus.EXPIRED;
        this.updatedAt = now;
    }
}
