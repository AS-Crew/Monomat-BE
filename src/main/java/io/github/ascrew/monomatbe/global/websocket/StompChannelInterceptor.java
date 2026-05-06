package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * STOMP 채널 인터셉터.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // 사용자 온라인 상태 TTL (단위: 시간)
    // 비정상 종료 시 Redis 좀비 키 방지용 — 정상 종료 시 handleDisconnectEvent에서 즉시 삭제
    private static final long USER_STATUS_TTL_HOURS = 2;

    // [추가] user_status 저장 및 메트릭 처리를 위한 의존성
    // StringRedisTemplate: 순수 문자열("ONLINE") 저장 — JSON 직렬화 방지
    private final StringRedisTemplate stringRedisTemplate;
    private final WebSocketMetric webSocketMetric;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        StompCommand command = accessor.getCommand();

        switch (command) {
            case CONNECT -> handleConnect(accessor, sessionAttributes);
            case SUBSCRIBE, SEND, UNSUBSCRIBE -> validateSession(accessor, sessionAttributes);
            case DISCONNECT -> handleDisconnect(sessionAttributes);
            default -> { /* 별도 처리 불필요 */ }
        }

        return message;
    }

    /**
     * CONNECT 명령 처리.
     */
    private void handleConnect(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes
    ) {
        String userIdentifier = accessor.getFirstNativeHeader(WebSocketHeaders.USER_IDENTIFIER);

        if (userIdentifier == null || userIdentifier.isBlank()) {
            log.warn("STOMP CONNECT 거부: 사용자 식별자 없음");
            throw new IllegalArgumentException(
                    "STOMP CONNECT: 사용자 식별자가 없습니다. 연결이 거부되었습니다.");
        }

        if (!UUID_PATTERN.matcher(userIdentifier).matches()) {
            log.warn("STOMP CONNECT 거부: 유효하지 않은 식별자 형식 = {}",
                    sanitizeForLog(userIdentifier));
            throw new IllegalArgumentException(
                    "STOMP CONNECT: 유효하지 않은 식별자 형식입니다. 연결이 거부되었습니다.");
        }

        // 1. 검증 완료 후 세션에 userIdentifier 저장 (이후 SEND/SUBSCRIBE에서 참조)
        if (sessionAttributes != null) {
            sessionAttributes.put(WebSocketHeaders.USER_IDENTIFIER, userIdentifier);
        }

        // 2. Redis user_status 저장
        // [수정] SessionConnectedEvent에서 이전 — Map 인스턴스 불일치 문제 근본 해결
        // [수정] stringRedisTemplate 사용 — "ONLINE" 순수 문자열로 저장 (JSON 직렬화 방지)
        stringRedisTemplate.opsForValue().set(
                RedisKeys.userStatusKey(userIdentifier),
                WebSocketHeaders.STATUS_ONLINE,
                USER_STATUS_TTL_HOURS,
                TimeUnit.HOURS
        );

        // 3. 활성 세션 카운터 증가 (Prometheus 메트릭)
        // [수정] SessionConnectedEvent에서 이전
        webSocketMetric.increment();

        log.info("STOMP CONNECT 성공: {}", userIdentifier);
    }

    private void validateSession(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes
    ) {
        String userIdentifier = sessionAttributes != null
                ? (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER)
                : null;

        if (userIdentifier == null) {
            log.warn("[{}] 인증되지 않은 세션 접근 차단", accessor.getCommand());
            throw new IllegalStateException("인증 정보가 존재하지 않습니다.");
        }

        switch (accessor.getCommand()) {
            case SUBSCRIBE -> handleSubscribe(accessor, sessionAttributes, userIdentifier);
            case SEND -> log.info("[SEND] 메시지 발송 - 식별자: {}, 경로: {}",
                    userIdentifier, accessor.getDestination());
            case UNSUBSCRIBE -> log.info("[UNSUBSCRIBE] 구독 해제 - 식별자: {}", userIdentifier);
            default -> { /* 별도 처리 불필요 */ }
        }
    }

    private void handleSubscribe(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes,
            String userIdentifier
    ) {
        String destination = accessor.getDestination();

        if (StompDestinations.isLobbySubscription(destination)) {
            String roomId = StompDestinations.extractLobbyCode(destination);
            if (sessionAttributes != null) {
                sessionAttributes.put(WebSocketHeaders.ROOM_ID, roomId);
            }
        }

        log.info("[SUBSCRIBE] 구독 - 식별자: {}, 경로: {}", userIdentifier, destination);
    }

    private void handleDisconnect(Map<String, Object> sessionAttributes) {
        String userIdentifier = sessionAttributes != null
                ? (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER)
                : WebSocketHeaders.UNKNOWN_IDENTIFIER;

        log.info("STOMP DISCONNECT: {}", userIdentifier);
    }

    private String sanitizeForLog(String value) {
        if (value == null) return "null";
        String sanitized = value.replaceAll("[\r\n\t]", "");
        return sanitized.length() > 50 ? sanitized.substring(0, 50) + "..." : sanitized;
    }
}
