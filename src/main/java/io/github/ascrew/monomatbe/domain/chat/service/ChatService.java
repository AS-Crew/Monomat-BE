/*
 * 채팅 메시지 처리 비즈니스 로직을 담당하는 서비스
 *
 * [책임]
 * - 세션에서 사용자 식별자 추출
 * - 클라이언트 메시지를 서버 신뢰 데이터로 재구성
 * - 클라이언트 sender / timestamp 위변조 방지
 * - 채팅 메시지 본문 검증
 * - 로비 채팅 전송 권한 검증
 * - Redis 기반 로비 채팅 쿨타임/반복 전송 제한
 * - 로비 최근 채팅 메시지 저장 위임
 * - Redis Pub/Sub 채널로 메시지 발행
 *
 * [주의]
 * 클라이언트가 전송한 sender, roomId, timestamp는 신뢰하지 않는다.
 * 서버에서 STOMP 세션과 MessageMapping 경로를 기준으로 신뢰 가능한 값으로 재구성한다.
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

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ChatService {

    /*
     * 채팅 메시지 최대 길이
     *
     * MAIN_CHAT.content가 VARCHAR(500) 기준이므로,
     * 향후 채팅 저장 정책이 추가되어도 payload와 DB 제약이 충돌하지 않도록 500자로 제한한다.
     */
    private static final int MAX_CHAT_CONTENT_LENGTH = 500;

    private static final String ERROR_MESSAGE_REQUIRED =
            "채팅 메시지를 입력해주세요.";
    private static final String ERROR_MESSAGE_TOO_LONG =
            "채팅 메시지는 500자 이하로 입력해주세요.";
    private static final String ERROR_INVALID_MESSAGE_TYPE =
            "일반 채팅으로 전송할 수 없는 메시지 타입입니다.";
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

    /**
     * 전체 채팅 메시지를 처리하고 Redis 전체 채팅 채널로 발행한다.
     *
     * [보안]
     * 클라이언트가 전송한 sender, roomId, timestamp를 신뢰하지 않고,
     * 서버에서 신뢰 가능한 값으로 재구성한다.
     *
     * [정책]
     * 전체 채팅은 특정 로비 참여 상태와 무관하므로 로비 참여자 검증을 수행하지 않는다.
     * 이번 이슈 범위는 로비 최근 채팅 조회이므로, 최근 채팅 저장은 로비 채팅에만 적용한다.
     *
     * @param message  클라이언트로부터 수신한 메시지
     * @param accessor STOMP 헤더 접근자
     */
    public void publishGlobalMessage(ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(accessor);
        ChatMessageDto secureMessage = buildUserChatMessage(message, "global", userIdentifier);

        redisPublisher.publish(StompDestinations.SUBSCRIBE_GLOBAL_CHAT, secureMessage);
    }

    /**
     * 로비 채팅 메시지를 처리하고 해당 로비 채널로 발행한다.
     *
     * [보안]
     * 클라이언트가 전송한 sender, roomId, timestamp를 신뢰하지 않고,
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

        ChatMessageDto secureMessage = buildUserChatMessage(message, code, userIdentifier);

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

    /**
     * 사용자가 직접 입력한 일반 채팅 메시지를 서버 신뢰 데이터로 재구성한다.
     *
     * [검증 정책]
     * - message null 차단
     * - content null 차단
     * - trim 후 빈 문자열 차단
     * - 최대 길이 초과 차단
     * - 일반 사용자 전송 타입은 CHAT만 허용
     *
     * [보안 정책]
     * - sender는 STOMP 세션의 userIdentifier로 덮어쓴다.
     * - roomId는 MessageMapping 경로 또는 서버 정책 값으로 덮어쓴다.
     * - timestamp는 서버 시각으로 생성한다.
     *
     * @param message        클라이언트 원본 메시지
     * @param roomId         서버가 확정한 수신 대상
     * @param userIdentifier 서버 세션에서 추출한 사용자 식별자
     * @return 서버 신뢰 데이터로 재구성된 일반 채팅 메시지
     */
    private ChatMessageDto buildUserChatMessage(
            ChatMessageDto message,
            String roomId,
            String userIdentifier
    ) {
        String normalizedContent = normalizeContent(message);
        validateUserMessageType(message);

        return ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(roomId)
                .sender(userIdentifier)
                .content(normalizedContent)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }

    /**
     * 채팅 본문을 검증하고 trim된 문자열을 반환한다.
     */
    private String normalizeContent(ChatMessageDto message) {
        if (message == null || message.getContent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_MESSAGE_REQUIRED);
        }

        String content = message.getContent().trim();

        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_MESSAGE_REQUIRED);
        }

        if (content.length() > MAX_CHAT_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_MESSAGE_TOO_LONG);
        }

        return content;
    }

    /**
     * 클라이언트가 일반 채팅 경로로 시스템 메시지를 위조하지 못하도록 타입을 제한한다.
     *
     * null 타입은 기존 FE 호환성을 위해 CHAT으로 간주한다.
     * 단, ENTER / LEAVE / KICK / SYSTEM / READY_CHANGED / HOST_CHANGED 같은 시스템 타입은
     * 서버 내부 로직에서만 생성되어야 하므로 일반 채팅 경로에서는 차단한다.
     */
    private void validateUserMessageType(ChatMessageDto message) {
        if (message.getType() == null) {
            return;
        }

        if (message.getType() != ChatMessageDto.MessageType.CHAT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_MESSAGE_TYPE);
        }
    }
}