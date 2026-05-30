package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 서버 신뢰 채팅 메시지 생성 컴포넌트
 *
 * [책임]
 * - 클라이언트가 보낸 sender, roomId, timestamp, messageId를 신뢰하지 않는다.
 * - STOMP 세션과 서버 정책을 기준으로 ChatMessageDto를 재구성한다.
 * - 신고 가능한 메시지 식별자와 발신자 스냅샷 필드를 채운다.
 *
 * [분리 이유]
 * ChatService가 권한 검증, rate limit, Redis 저장, Pub/Sub 발행까지 담당하고 있으므로
 * 메시지 조립/검증 책임을 별도 컴포넌트로 분리한다.
 */
@Component
@RequiredArgsConstructor
public class TrustedChatMessageFactory {

    private static final DateTimeFormatter CHAT_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                    .withZone(ZoneOffset.UTC);

    private static final int MAX_CHAT_CONTENT_LENGTH = 500;

    private static final String ERROR_MESSAGE_REQUIRED =
            "채팅 메시지를 입력해주세요.";
    private static final String ERROR_MESSAGE_TOO_LONG =
            "채팅 메시지는 500자 이하로 입력해주세요.";
    private static final String ERROR_INVALID_MESSAGE_TYPE =
            "일반 채팅으로 전송할 수 없는 메시지 타입입니다.";

    private final ChatMessageIdGenerator chatMessageIdGenerator;
    private final ChatSenderProfileResolver chatSenderProfileResolver;
    private final Clock clock = Clock.systemUTC();

    /**
     * 사용자가 직접 입력한 일반 채팅 메시지를 서버 신뢰 데이터로 재구성한다.
     *
     * @param message        클라이언트 원본 메시지
     * @param roomId         서버가 확정한 수신 대상
     * @param userIdentifier 서버 세션에서 추출한 사용자 식별자
     * @return 서버 신뢰 데이터로 재구성된 일반 채팅 메시지
     */
    public ChatMessageDto createUserChatMessage(
            ChatMessageDto message,
            String roomId,
            String userIdentifier
    ) {
        String normalizedContent = normalizeContent(message);
        validateUserMessageType(message);

        String sentAt = currentTimestamp();
        ChatSenderProfile senderProfile = chatSenderProfileResolver.resolve(userIdentifier);

        return ChatMessageDto.builder()
                .messageId(chatMessageIdGenerator.generate())
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(roomId)
                .sender(userIdentifier)
                .senderId(senderProfile.getUserId())
                .senderNickname(senderProfile.getNickname())
                .content(normalizedContent)
                .timestamp(sentAt)
                .sentAt(sentAt)
                .build();
    }

    private String currentTimestamp() {
        return CHAT_TIMESTAMP_FORMATTER.format(Instant.now(clock));
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