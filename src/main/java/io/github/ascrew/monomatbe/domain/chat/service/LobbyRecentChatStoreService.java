package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.chat.config.LobbyRecentChatProperties;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 로비 최근 채팅 메시지 저장 서비스
 *
 * [책임]
 * - 서버가 신뢰 가능한 값으로 재구성한 로비 채팅 메시지를 Redis List에 저장한다.
 * - 로비별 최근 N개 메시지만 유지한다.
 * - 최근 채팅 List TTL을 갱신한다.
 *
 * [장애 정책]
 * 최근 채팅은 새로고침/늦은 입장 UX 보조 기능
 * 따라서 Redis 저장 실패가 발생하더라도 실시간 채팅 전송 자체를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyRecentChatStoreService {

    private static final String LUA_RESULT_OK = "OK";

    private final StringRedisTemplate redisTemplate;

    @Qualifier("cacheJsonMapper")
    private final JsonMapper jsonMapper;

    @Qualifier("appendRecentLobbyChatScript")
    private final RedisScript<String> appendRecentLobbyChatScript;

    private final LobbyRecentChatProperties properties;

    /**
     * 로비 최근 채팅 메시지를 Redis List에 저장한다.
     *
     * 저장 순서:
     * 1. ChatMessageDto를 타입 정보 없는 JSON 문자열로 직렬화
     * 2. Lua에서 RPUSH + LTRIM + EXPIRE를 원자 처리
     *
     * @param lobbyCode 로비 초대 코드
     * @param message 서버에서 신뢰 가능한 값으로 재구성된 채팅 메시지
     */
    public void append(String lobbyCode, ChatMessageDto message) {
        try {
            appendOrThrow(lobbyCode, message);
        } catch (RecentChatSerializationException | RecentChatLuaContractException e) {
            log.error(
                    "로비 최근 채팅 저장 로직 오류 - lobbyCode: {}, sender: {}",
                    lobbyCode,
                    message != null ? message.getSender() : null,
                    e
            );
        } catch (RuntimeException e) {
            log.warn(
                    "로비 최근 채팅 Redis 저장 실패 - lobbyCode: {}, sender: {}",
                    lobbyCode,
                    message != null ? message.getSender() : null,
                    e
            );
        }
    }

    private void appendOrThrow(String lobbyCode, ChatMessageDto message) {
        String key = RedisKeys.lobbyRecentChatMessagesKey(lobbyCode);
        String payload = serialize(message);

        String result = redisTemplate.execute(
                appendRecentLobbyChatScript,
                List.of(key),
                payload,
                String.valueOf(properties.getMaxSize()),
                String.valueOf(properties.getTtl().toSeconds())
        );

        if (!LUA_RESULT_OK.equals(result)) {
            throw new RecentChatLuaContractException("로비 최근 채팅 저장 Lua 처리 실패: " + result);
        }
    }

    private String serialize(ChatMessageDto message) {
        try {
            return jsonMapper.writeValueAsString(message);
        } catch (JacksonException e) {
            throw new RecentChatSerializationException("로비 최근 채팅 메시지 직렬화에 실패했습니다.", e);
        }
    }

    private static class RecentChatSerializationException extends RuntimeException {
        private RecentChatSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class RecentChatLuaContractException extends RuntimeException {
        private RecentChatLuaContractException(String message) {
            super(message);
        }
    }
}