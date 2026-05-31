package io.github.ascrew.monomatbe.domain.auth.service;

/**
 * userIdentifier 기준 사용자 프로필 스냅샷
 *
 * @param userId   users.id
 * @param nickname 사용자 표시 닉네임
 */
public record UserIdentifierProfile(
        Long userId,
        String nickname
) {
}