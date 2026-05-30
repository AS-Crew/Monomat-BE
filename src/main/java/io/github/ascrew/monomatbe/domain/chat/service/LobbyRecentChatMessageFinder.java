package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * 로비 최근 채팅 메시지 단건 조회 서비스
 *
 * [책임]
 * - Redis 최근 채팅 List에서 messageId에 해당하는 메시지를 찾는다.
 * - 신고 API가 Redis 저장 구조를 직접 알지 않도록 조회 책임을 분리한다.
 *
 * [장애 정책]
 * - Redis 조회 실패 또는 payload 역직렬화 실패는 Optional.empty()로 처리한다.
 * - 신고 서비스는 empty를 "신고 대상 메시지를 찾을 수 없음"으로 해석한다.
 *
 * [주의]
 * 권한 검증은 수행하지 않는다.
 * 신고자 권한 검증은 LobbyChatMessageReportService에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyRecentChatMessageFinder {

    private final StringRedisTemplate redisTemplate;

    @Qualifier("cacheJsonMapper")
    private final JsonMapper jsonMapper;

    /**
     * 로비 최근 채팅 Redis List에서 messageId에 해당하는 메시지를 찾는다.
     *
     * @param lobbyCode 로비 초대 코드
     * @param messageId 채팅 메시지 ID
     * @return 찾은 메시지 Optional
     */
    public Optional<ChatMessageDto> findByMessageId(String lobbyCode, String messageId) {
        if (lobbyCode == null || lobbyCode.isBlank() || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }

        try {
            return findByMessageIdOrEmpty(lobbyCode, messageId);
        } catch (RuntimeException e) {
            log.warn(
                    "로비 최근 채팅 단건 조회 실패 - lobbyCode: {}, messageId: {}",
                    lobbyCode,
                    messageId,
                    e
            );
            return Optional.empty();
        }
    }

    private Optional<ChatMessageDto> findByMessageIdOrEmpty(String lobbyCode, String messageId) {
        String key = RedisKeys.lobbyRecentChatMessagesKey(lobbyCode);
        List<String> payloads = redisTemplate.opsForList().range(key, 0, -1);

        if (payloads == null || payloads.isEmpty()) {
            return Optional.empty();
        }

        return payloads.stream()
                .map(payload -> deserializeOrNull(lobbyCode, payload))
                .filter(message -> hasMessageId(message, messageId))
                .findFirst();
    }

    private ChatMessageDto deserializeOrNull(String lobbyCode, String payload) {
        try {
            return jsonMapper.readValue(payload, ChatMessageDto.class);
        } catch (JacksonException e) {
            log.warn(
                    "로비 최근 채팅 payload 역직렬화 실패 - 해당 메시지 건너뜀. lobbyCode: {}, payloadLength: {}",
                    lobbyCode,
                    payload != null ? payload.length() : null,
                    e
            );
            return null;
        }
    }

    private boolean hasMessageId(ChatMessageDto message, String messageId) {
        return message != null && messageId.equals(message.getMessageId());
    }
}