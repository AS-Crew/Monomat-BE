package io.github.ascrew.monomatbe.domain.auth.repository;

/**
 * userIdentifier와 사용자 닉네임만 조회하기 위한 읽기 전용 Projection
 *
 * [사용 목적]
 * 로비 상세 조회에서 참여자 닉네임을 표시할 때 세션 엔티티 전체를 로딩하지 않고,
 * 화면 표시에 필요한 userIdentifier와 nickname만 조회한다.
 */
public interface UserIdentifierNicknameProjection {

    /**
     * Redis/WebSocket에서 사용하는 사용자 식별자
     */
    String getUserIdentifier();

    /**
     * FE 대기실 참여자 목록에 표시할 사용자 닉네임
     */
    String getNickname();
}