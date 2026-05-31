/*
 * 채팅 메시지 처리 비즈니스 로직을 담당하는 서비스
 *
 * [책임]
 * - 세션에서 사용자 식별자 추출
 * - 로비 채팅 전송 권한 검증
 * - Redis 기반 로비 채팅 쿨타임/반복 전송 제한
 * - 로비 최근 채팅 메시지 저장 위임
 * - Redis Pub/Sub 채널로 메시지 발행
 *
 * [주의]
 * 클라이언트가 전송한 sender, roomId, timestamp, messageId는 신뢰하지 않는다.
 * TrustedChatMessageFactory에서 STOMP 세션과 MessageMapping 경로를 기준으로
 * 신뢰 가능한 값으로 재구성한다.
 */
package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.WebSocketSessionUtils;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String GLOBAL_ROOM_ID = "global";

    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_CHAT_FORBIDDEN =
            "로비 참여자만 로비 채팅을 보낼 수 있습니다.";
    private static final String ERROR_LOBBY_CHAT_KICKED =
            "강퇴된 로비에는 채팅을 보낼 수 없습니다.";

    private final RedisPublisher redisPublisher;
    private final LobbyRepository lobbyRepository;
    private final LobbyChatRateLimitService lobbyChatRateLimitService;
    private final LobbyRecentChatStoreService lobbyRecentChatStoreService;
    private final TrustedChatMessageFactory trustedChatMessageFactory;

    /**
     * 전체 채팅 메시지를 처리하고 Redis 전체 채팅 채널로 발행한다.
     *
     * [보안]
     * 클라이언트가 전송한 sender, roomId, timestamp, messageId를 신뢰하지 않고,
     * 서버에서 신뢰 가능한 값으로 재구성한다.
     *
     * [정책]
     * 전체 채팅은 특정 로비 참여 상태와 무관하므로 로비 참여자 검증을 수행하지 않는다.
     * 최근 채팅 저장은 로비 채팅에만 적용한다.
     *
     * @param message  클라이언트로부터 수신한 메시지
     * @param accessor STOMP 헤더 접근자
     */
    public void publishGlobalMessage(ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(accessor);

        ChatMessageDto secureMessage = trustedChatMessageFactory.createUserChatMessage(
                message,
                GLOBAL_ROOM_ID,
                userIdentifier
        );

        redisPublisher.publish(StompDestinations.SUBSCRIBE_GLOBAL_CHAT, secureMessage);
    }

    /**
     * 로비 채팅 메시지를 처리하고 해당 로비 채널로 발행한다.
     *
     * [보안]
     * 클라이언트가 전송한 sender, roomId, timestamp, messageId를 신뢰하지 않고,
     * 서버에서 신뢰 가능한 값으로 재구성한다.
     *
     * [권한]
     * - 존재하는 로비에 대해서만 전송할 수 있다.
     * - 강퇴된 사용자는 전송할 수 없다.
     * - 현재 로비 참여자만 전송할 수 있다.
     *
     * [도배 방지]
     * - Redis 기반 쿨타임을 적용한다.
     * - 짧은 시간 내 동일 메시지 반복 전송을 차단한다.
     *
     * [최근 채팅 저장]
     * - 서버 신뢰 메시지를 Redis List에 저장한다.
     * - 저장 실패는 실시간 채팅 전송을 막지 않는다.
     *
     * @param code     로비 초대 코드
     * @param message  클라이언트로부터 수신한 메시지
     * @param accessor STOMP 헤더 접근자
     */
    public void publishLobbyMessage(
            String code,
            ChatMessageDto message,
            SimpMessageHeaderAccessor accessor
    ) {
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(accessor);

        validateLobbyChatPermission(code, userIdentifier);

        ChatMessageDto secureMessage = trustedChatMessageFactory.createUserChatMessage(
                message,
                code,
                userIdentifier
        );

        lobbyChatRateLimitService.validateAndRecord(
                code,
                userIdentifier,
                secureMessage.getContent()
        );

        lobbyRecentChatStoreService.append(code, secureMessage);

        redisPublisher.publish(StompDestinations.subscribeLobbyChat(code), secureMessage);
    }

    /**
     * 로비 채팅 전송 권한을 검증한다.
     *
     * [검증 순서]
     * 1. 로비 존재 여부 확인
     * 2. 강퇴 여부 확인
     * 3. 현재 참여자 여부 확인
     *
     * [강퇴 여부를 참여자 여부보다 먼저 확인하는 이유]
     * 강퇴 처리 후에는 participants Set에서 제거되고 kicked Set에 추가될 수 있다.
     * 이때 참여자 검증을 먼저 수행하면 강퇴 유저도 단순 미참여자로만 처리되어
     * 클라이언트가 정확한 사유를 알기 어렵다.
     */
    private void validateLobbyChatPermission(String code, String userIdentifier) {
        if (!lobbyRepository.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_LOBBY_NOT_FOUND);
        }

        if (lobbyRepository.isKicked(code, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_LOBBY_CHAT_KICKED);
        }

        if (!lobbyRepository.isParticipant(code, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_LOBBY_CHAT_FORBIDDEN);
        }
    }
}