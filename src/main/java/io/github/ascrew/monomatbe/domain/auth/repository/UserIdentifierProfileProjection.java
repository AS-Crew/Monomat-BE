package io.github.ascrew.monomatbe.domain.auth.repository;

/**
 * userIdentifier 기준 사용자 프로필 스냅샷 조회 Projection
 *
 * [사용 목적]
 * 로비 채팅 메시지를 Redis에 저장할 때 신고 스냅샷에 필요한
 * users.id와 닉네임을 함께 조회한다.
 */
public interface UserIdentifierProfileProjection {

    /**
     * Redis/WebSocket에서 사용하는 사용자 식별자
     */
    String getUserIdentifier();

    /**
     * users.id
     */
    Long getUserId();

    /**
     * 사용자 표시 닉네임
     */
    String getNickname();
}