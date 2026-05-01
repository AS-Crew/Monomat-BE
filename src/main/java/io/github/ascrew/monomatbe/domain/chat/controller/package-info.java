/**
 * 채팅 관련 WebSocket 컨트롤러 패키지.
 *
 * <p>STOMP @MessageMapping을 통해 클라이언트의 채팅 메시지를 수신하고
 * {@link io.github.ascrew.monomatbe.domain.chat.service.ChatService}에 위임합니다.
 * 컨트롤러는 라우팅만 담당하며 비즈니스 로직을 포함하지 않습니다.
 */
package io.github.ascrew.monomatbe.domain.chat.controller;