/**
 * 채팅 도메인의 데이터 전송 객체(DTO) 패키지.
 *
 * <p>WebSocket 채팅 메시지 송수신에 사용되는 DTO를 포함합니다.
 * Redis Pub/Sub을 통해 직렬화/역직렬화되므로
 * 모든 DTO는 기본 생성자를 반드시 포함해야 합니다.
 */
package io.github.ascrew.monomatbe.domain.chat.dto;