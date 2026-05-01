/*
 * 채팅 메시지 처리 비즈니스 로직을 담당하는 서비스.
 *
 * [ChatController에서 이전된 책임]
 * - 세션에서 사용자 식별자 추출
 * - 클라이언트 메시지를 서버 신뢰 데이터로 재구성 (발신자 위변조 방지)
 * - Redis Pub/Sub 채널로 메시지 발행
 *
 * [리팩토링 변경 사항]
 * - extractUserIdentifier() private 메서드 제거
 *   → WebSocketSessionUtils.extractUserIdentifier()로 위임
 *   → 동일 로직이 WebSocketEventListener에도 존재하던 중복을 제거
 */
package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.WebSocketSessionUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final RedisPublisher redisPublisher;

    /**
     * 전체 채팅 메시지를 처리하고 Redis 전체 채팅 채널로 발행합니다.
     *
     * [보안]
     * 클라이언트가 전송한 sender 필드를 신뢰하지 않고,
     * 서버 세션에서 추출한 식별자로 덮어씁니다.
     *
     * @param message  클라이언트로부터 수신한 메시지 (sender 필드는 신뢰하지 않음)
     * @param accessor STOMP 헤더 접근자 (세션에서 사용자 식별자 추출용)
     */
    public void publishGlobalMessage(ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        // [수정] private extractUserIdentifier() 제거
        //        → WebSocketSessionUtils 정적 메서드로 위임하여 중복 로직 제거
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(accessor);
        ChatMessageDto secureMessage = buildSecureMessage(message, "global", userIdentifier);
        redisPublisher.publish(StompDestinations.SUBSCRIBE_GLOBAL_CHAT, secureMessage);
    }

    /**
     * 로비 채팅 메시지를 처리하고 해당 로비 채널로 발행합니다.
     *
     * [보안]
     * 클라이언트가 전송한 sender 필드를 신뢰하지 않고,
     * 서버 세션에서 추출한 식별자로 덮어씁니다.
     *
     * @param code     로비 초대 코드
     * @param message  클라이언트로부터 수신한 메시지 (sender 필드는 신뢰하지 않음)
     * @param accessor STOMP 헤더 접근자 (세션에서 사용자 식별자 추출용)
     */
    public void publishLobbyMessage(
            String code,
            ChatMessageDto message,
            SimpMessageHeaderAccessor accessor
    ) {
        // [수정] private extractUserIdentifier() 제거
        //        → WebSocketSessionUtils 정적 메서드로 위임하여 중복 로직 제거
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(accessor);
        ChatMessageDto secureMessage = buildSecureMessage(message, code, userIdentifier);
        redisPublisher.publish(StompDestinations.subscribeLobbyChat(code), secureMessage);
    }

    /**
     * 클라이언트 메시지를 서버 신뢰 데이터로 재구성합니다.
     *
     * [보안 — 발신자 위변조 방지]
     * 클라이언트가 전송한 sender 필드를 그대로 사용하지 않고,
     * 서버 세션에서 추출한 userIdentifier로 반드시 덮어씁니다.
     * 이를 통해 다른 사용자를 사칭하는 발신자 위변조를 원천 차단합니다.
     *
     * @param message        원본 클라이언트 메시지
     * @param roomId         수신 대상 방 코드 ("global" 또는 로비 코드)
     * @param userIdentifier 세션에서 추출한 신뢰할 수 있는 사용자 식별자
     * @return 서버 신뢰 데이터로 재구성된 메시지
     */
    private ChatMessageDto buildSecureMessage(
            ChatMessageDto message,
            String roomId,
            String userIdentifier
    ) {
        return ChatMessageDto.builder()
                .type(message.getType())
                .roomId(roomId)
                // 클라이언트 sender 무시, 서버 세션 식별자로 교체
                .sender(userIdentifier)
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .build();
    }
}