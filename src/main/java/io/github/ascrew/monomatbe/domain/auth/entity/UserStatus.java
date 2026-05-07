package io.github.ascrew.monomatbe.domain.auth.entity;

/**
 * 계정 상태.
 * - ACTIVE: 정상 사용 가능
 * - BANNED: 운영 정책상 차단
 * - DELETED: 탈퇴/삭제 처리
 */
public enum UserStatus {
    ACTIVE,
    BANNED,
    DELETED
}
