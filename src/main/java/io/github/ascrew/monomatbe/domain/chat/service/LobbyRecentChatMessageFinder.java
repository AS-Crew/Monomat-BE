package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
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
 * - messageId가 Redis 최근 채팅 목록에 없으면 Optional.empty()를 반환한다.
 * - Redis 연결 실패, Redis 시스템 오류, Redis 조회 중 알 수 없는 런타임 예외는
 *   RecentChatMessageLookupException으로 올린다.
 * - 개별 payload 역직렬화 실패는 해당 payload만 건너뛴다.
 *
 * [주의]
 * 권한 검증은 수행하지 않는다.
 * 신고자 권한 검증은 LobbyChatMessageReportService에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyRecentChatMessageFinder {

    private static final String ERROR_RECENT_CHAT_LOOKUP_FAILED =
            "로비 최근 채팅 저장소를 조회할 수 없습니다.";

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
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            throw new RecentChatMessageLookupException(ERROR_RECENT_CHAT_LOOKUP_FAILED, e);
        } catch (RuntimeException e) {
            throw new RecentChatMessageLookupException(ERROR_RECENT_CHAT_LOOKUP_FAILED, e);
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