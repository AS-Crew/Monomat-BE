package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    // 표준 UUID 형식 검증 정규식 (8-4-4-4-12 포맷)
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
     * STOMP 헤더에서 사용자 식별자를 추출하고 형식을 검증한 뒤 세션에 저장합니다.
     * 검증 실패 시 예외를 던져 연결을 거부합니다.
     */
    private void handleConnect(StompHeaderAccessor accessor, Map<String, Object> sessionAttributes) {
        // WebSocketHeaders 상수로 헤더 키 참조 (이슈 #3 네이밍 통일과 연계)
        String userIdentifier = accessor.getFirstNativeHeader(WebSocketHeaders.USER_IDENTIFIER);

        if (userIdentifier == null || userIdentifier.isBlank()) {
            log.warn("STOMP CONNECT 거부: 사용자 식별자 없음");
            throw new IllegalArgumentException("STOMP CONNECT: 사용자 식별자가 없습니다. 연결이 거부되었습니다.");
        }

        if (!UUID_PATTERN.matcher(userIdentifier).matches()) {
            log.warn("STOMP CONNECT 거부: 유효하지 않은 식별자 형식 = {}", sanitizeForLog(userIdentifier));
            throw new IllegalArgumentException("STOMP CONNECT: 유효하지 않은 식별자 형식입니다. 연결이 거부되었습니다.");
        }

        // 검증 완료 후 세션에 사용자 식별자 저장
        if (sessionAttributes != null) {
            sessionAttributes.put(WebSocketHeaders.USER_IDENTIFIER, userIdentifier);
        }

        log.info("STOMP CONNECT 성공: {}", userIdentifier);
    }

    /**
     * SUBSCRIBE / SEND / UNSUBSCRIBE 명령 처리.
     * 세션에 사용자 식별자가 존재하는지 검증합니다.
     */
    private void validateSession(StompHeaderAccessor accessor, Map<String, Object> sessionAttributes) {
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

    /**
     * SUBSCRIBE 명령 처리.
     * 로비 채팅 채널 구독 시 세션에 로비 코드를 저장합니다.
     * DISCONNECT 이벤트에서 퇴장 처리 시 사용됩니다.
     */
    private void handleSubscribe(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes,
            String userIdentifier
    ) {
        String destination = accessor.getDestination();

        // StompDestinations 상수로 경로 접두사 참조
        if (StompDestinations.isLobbySubscription(destination)) {
            String roomId = StompDestinations.extractLobbyCode(destination);

            if (sessionAttributes != null) {
                // WebSocketHeaders 상수로 세션 키 참조
                sessionAttributes.put(WebSocketHeaders.ROOM_ID, roomId);
            }
        }

        log.info("[SUBSCRIBE] 구독 - 식별자: {}, 경로: {}", userIdentifier, destination);
    }

    /**
     * DISCONNECT 명령 처리. 연결 해제 로그를 남깁니다.
     */
    private void handleDisconnect(Map<String, Object> sessionAttributes) {
        String userIdentifier = sessionAttributes != null
                ? (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER)
                : WebSocketHeaders.UNKNOWN_IDENTIFIER;

        log.info("STOMP DISCONNECT: {}", userIdentifier);
    }

    /**
     * 로그 출력 전 입력값을 안전하게 정제합니다.
     * 로그 인젝션 방지를 위해 개행 문자를 제거하고 길이를 제한합니다.
     */
    private String sanitizeForLog(String value) {
        if (value == null) return "null";
        String sanitized = value.replaceAll("[\r\n\t]", "");
        return sanitized.length() > 50 ? sanitized.substring(0, 50) + "..." : sanitized;
    }
}