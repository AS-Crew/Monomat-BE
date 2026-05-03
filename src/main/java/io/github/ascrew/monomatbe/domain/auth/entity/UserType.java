package io.github.ascrew.monomatbe.domain.auth.entity;

/**
 * 사용자 유형.
 * - REGISTERED: 회원가입/로그인 완료 사용자
 * - GUEST: 닉네임 기반 임시 사용자
 */
public enum UserType {
    REGISTERED,
    GUEST
}
