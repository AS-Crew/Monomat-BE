package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 로비 최근 채팅 메시지 조회 서비스
 *
 * [책임]
 * - 최근 채팅 조회 권한을 검증한다.
 * - Redis List에 저장된 최근 채팅 JSON을 ChatMessageDto로 역직렬화한다.
 * - Redis 장애 또는 일부 깨진 payload가 있어도 로비 입장 UX를 과도하게 막지 않는다.
 *
 * [장애 정책]
 * 최근 채팅 조회는 새로고침/늦은 입장 UX 보조 기능이다.
 * 따라서 Redis 조회 실패 시 빈 목록을 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyRecentChatQueryService {

    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_CHAT_FORBIDDEN =
            "로비 참여자만 최근 채팅을 조회할 수 있습니다.";
    private static final String ERROR_LOBBY_CHAT_KICKED =
            "강퇴된 로비의 최근 채팅은 조회할 수 없습니다.";

    private final StringRedisTemplate redisTemplate;

    @Qualifier("cacheJsonMapper")
    private final JsonMapper jsonMapper;

    private final LobbyRepository lobbyRepository;

    /**
     * 로비 최근 채팅 메시지를 조회한다.
     *
     * @param lobbyCode 로비 초대 코드
     * @param userIdentifier 요청자 userIdentifier
     * @return 오래된 메시지부터 최신 메시지 순서의 최근 채팅 목록
     */
    public List<ChatMessageDto> getRecentMessages(String lobbyCode, String userIdentifier) {
        validateReadPermission(lobbyCode, userIdentifier);

        try {
            return getRecentMessagesOrEmpty(lobbyCode);
        } catch (RuntimeException e) {
            log.warn(
                    "로비 최근 채팅 조회 실패 - 빈 목록 반환. lobbyCode: {}, userIdentifier: {}",
                    lobbyCode,
                    userIdentifier,
                    e
            );
            return Collections.emptyList();
        }
    }

    private void validateReadPermission(String lobbyCode, String userIdentifier) {
        if (!lobbyRepository.existsByCode(lobbyCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_LOBBY_NOT_FOUND);
        }

        if (lobbyRepository.isKicked(lobbyCode, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_LOBBY_CHAT_KICKED);
        }

        if (!lobbyRepository.isParticipant(lobbyCode, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_LOBBY_CHAT_FORBIDDEN);
        }
    }

    private List<ChatMessageDto> getRecentMessagesOrEmpty(String lobbyCode) {
        String key = RedisKeys.lobbyRecentChatMessagesKey(lobbyCode);
        List<String> payloads = redisTemplate.opsForList().range(key, 0, -1);

        if (payloads == null || payloads.isEmpty()) {
            return Collections.emptyList();
        }

        return payloads.stream()
                .map(payload -> deserializeOrNull(lobbyCode, payload))
                .filter(Objects::nonNull)
                .toList();
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
}