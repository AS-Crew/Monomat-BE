/**
 * 애플리케이션 전역 상수 패키지.
 *
 * <p>하드코딩된 매직 스트링을 방지하기 위해 Redis 키, STOMP 경로,
 * WebSocket 헤더 키를 상수 클래스로 중앙화하여 관리합니다.
 * <ul>
 *   <li>{@code RedisKeys} : Redis 키 패턴 상수 및 팩토리 메서드</li>
 *   <li>{@code StompDestinations} : STOMP 송신/구독 경로 상수</li>
 *   <li>{@code WebSocketHeaders} : WebSocket 헤더 키 및 세션 속성 키 상수</li>
 * </ul>
 */
package io.github.ascrew.monomatbe.global.constant;