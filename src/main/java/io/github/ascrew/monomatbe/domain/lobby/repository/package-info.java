/**
 * 로비 도메인의 데이터 접근 계층 패키지.
 *
 * <p>실시간 게임 데이터 처리를 위해 Redis와 직접 통신합니다.
 * Lettuce 클라이언트를 사용하여 가상 스레드 핀닝 없이 비동기 I/O를 처리하며,
 * 원자성이 필요한 연산은 Lua 스크립트로 처리합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;