/*
 * 로비 채팅 전송 제한 정책을 담당하는 서비스
 *
 * [책임]
 * - Redis 기반 로비 채팅 쿨타임 적용
 * - 같은 메시지 단기 반복 전송 방지
 *
 * [Redis를 사용하는 이유]
 * WebSocket 서버가 여러 인스턴스로 확장되더라도 동일 사용자의 도배 제한이
 * 모든 서버에서 일관되게 적용되어야 한다.
 * 따라서 서버 메모리가 아니라 Redis를 기준으로 제한 상태를 관리한다.
 */
package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyChatRateLimitService {

    /*
     * 같은 사용자가 같은 로비에 채팅을 연속 전송할 수 없는 최소 간격
     *
     * 1초는 일반 채팅 UX를 크게 해치지 않으면서,
     * 매크로나 Enter 연타성 도배를 차단하기에 적절한 기본값이다.
     */
    private static final Duration CHAT_COOLDOWN_TTL = Duration.ofSeconds(1);

    /*
     * 같은 사용자가 같은 로비에 동일 메시지를 반복 전송할 수 없는 기간
     *
     * 5초는 일반적인 실수성 재전송은 어느 정도 허용하면서,
     * 짧은 시간 내 같은 문구 도배를 막는 현실적인 값이다.
     */
    private static final Duration REPEATED_MESSAGE_TTL = Duration.ofSeconds(5);

    private static final String REDIS_LOCK_VALUE = "1";

    private static final String ERROR_CHAT_TOO_FAST =
            "채팅을 너무 빠르게 전송하고 있습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_CHAT_REPEATED =
            "같은 메시지를 짧은 시간 안에 반복해서 보낼 수 없습니다.";
    private static final String ERROR_CHAT_LIMIT_UNAVAILABLE =
            "채팅 제한 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.";

    private final StringRedisTemplate redisTemplate;

    /**
     * 로비 채팅 전송 제한을 검증하고, 통과 시 현재 메시지 전송 상태를 Redis에 기록한다.
     *
     * [처리 순서]
     * 1. 쿨타임 SET NX 시도
     * 2. 실패하면 429 TOO_MANY_REQUESTS
     * 3. 최근 메시지 해시 조회
     * 4. 동일 메시지가 반복되면 429 TOO_MANY_REQUESTS
     * 5. 현재 메시지 해시를 TTL과 함께 저장
     *
     * [주의]
     * 이 메서드는 실제 채팅 발행 직전에 호출되어야 한다.
     * 그래야 제한을 통과한 메시지만 Redis Pub/Sub으로 발행된다.
     *
     * @param lobbyCode 로비 초대 코드
     * @param userIdentifier 사용자 식별자
     * @param content 정규화된 채팅 본문
     */
    public void validateAndRecord(
            String lobbyCode,
            String userIdentifier,
            String content
    ) {
        try {
            validateCooldown(lobbyCode, userIdentifier);
            validateRepeatedMessage(lobbyCode, userIdentifier, content);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error(
                    "로비 채팅 제한 Redis 처리 실패 - lobbyCode: {}, userIdentifier: {}",
                    lobbyCode,
                    userIdentifier,
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ERROR_CHAT_LIMIT_UNAVAILABLE,
                    e
            );
        }
    }

    /**
     * 채팅 쿨타임을 검증
     *
     * SET NX를 사용하여 동일 사용자의 동시 요청이 들어와도
     * Redis에서 원자적으로 1개 요청만 통과시킨다.
     */
    private void validateCooldown(String lobbyCode, String userIdentifier) {
        String cooldownKey = RedisKeys.lobbyChatCooldownKey(lobbyCode, userIdentifier);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                cooldownKey,
                REDIS_LOCK_VALUE,
                CHAT_COOLDOWN_TTL
        );

        if (!Boolean.TRUE.equals(acquired)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    ERROR_CHAT_TOO_FAST
            );
        }
    }

    /**
     * 같은 메시지의 단기 반복 전송을 검증
     *
     * 메시지 원문을 Redis에 그대로 저장하지 않고 SHA-256 해시만 저장한다.
     * 채팅 본문이 개인정보를 포함할 수 있으므로, 제한 검증 목적에는 해시 저장이 더 안전하다.
     */
    private void validateRepeatedMessage(
            String lobbyCode,
            String userIdentifier,
            String content
    ) {
        String recentMessageKey = RedisKeys.lobbyChatRecentMessageKey(lobbyCode, userIdentifier);
        String currentMessageHash = hashContent(content);

        String previousMessageHash = redisTemplate.opsForValue().get(recentMessageKey);

        if (currentMessageHash.equals(previousMessageHash)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    ERROR_CHAT_REPEATED
            );
        }

        redisTemplate.opsForValue().set(
                recentMessageKey,
                currentMessageHash,
                REPEATED_MESSAGE_TTL
        );
    }

    /**
     * 채팅 본문을 SHA-256 해시 문자열로 변환한다.
     */
    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}