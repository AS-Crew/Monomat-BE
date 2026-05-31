package io.github.ascrew.monomatbe.domain.auth.entity;

/**
 * 사용자 서비스 권한
 *
 * [역할]
 * - USER  : 일반 사용자 권한
 * - ADMIN : 운영자/관리자 권한
 *
 * [주의]
 * userType은 게스트/정식 회원 같은 계정 유형을 의미하고,
 * userRole은 서비스 내 인가 권한을 의미한다.
 */
public enum UserRole {

    /**
     * 일반 사용자
     */
    USER,

    /**
     * 관리자
     */
    ADMIN
}