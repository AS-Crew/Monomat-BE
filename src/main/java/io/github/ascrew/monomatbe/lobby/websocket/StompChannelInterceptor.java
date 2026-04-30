package io.github.ascrew.monomatbe.lobby.websocket;

import io.github.ascrew.monomatbe.common.constant.WebSocketConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Component
public class StompChannelInterceptor implements ChannelInterceptor {
    // 표준 uuid 형식을 검사하는 정규식 (8-4-4-4-12 포멧)
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && accessor.getCommand() != null) {
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

            StompCommand command = accessor.getCommand();

            //유저의 행동(Command)에 따라 감시 및 차단 로직
            switch (command) {
                case CONNECT -> handleConnect(accessor, sessionAttributes);
                case SUBSCRIBE,SEND,UNSUBSCRIBE -> validateSession(accessor, sessionAttributes);
                case DISCONNECT -> handleDisconnect(sessionAttributes);
                default -> {}
            }
        }
        return message; // 메시지를 그대로 반환하여 STOMP 메시지 처리를 계속 진행
    }

    private void validateSession(StompHeaderAccessor accessor, Map<String, Object> sessionAttributes) {

        if (sessionAttributes == null || sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID) == null) {
            log.warn("[{} 차단] 인증되지 않은 세션 접근", accessor.getCommand());
            throw new IllegalStateException("세션 인증 정보가 존재하지 않습니다.");
        }

        String uuid = (String) sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID);

        // 정상 로직 수행 (로그 기록 등)
        switch (Objects.requireNonNull(accessor.getCommand())) {
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (destination != null && destination.startsWith(WebSocketConstants.LOBBY_TOPIC_PREFIX)) {
                    String roomId = destination.substring(WebSocketConstants.LOBBY_TOPIC_PREFIX.length());
                    sessionAttributes.put(WebSocketConstants.SESSION_ATTR_ROOM_ID, roomId);
                }
                log.info("[SUBSCRIBE] 방 입장 - UUID: {}, Destination: {}", uuid, destination);
            }
            case SEND -> log.info("[SEND] 메시지 발송 - UUID: {}, Destination: {}", uuid, accessor.getDestination());
            case UNSUBSCRIBE -> log.info("[UNSUBSCRIBE] 방 퇴장 - UUID: {}", uuid);
            default -> {}
        }
    }

    private String sanitizeForLog(String uuid) {
        if (uuid == null) {
            return "null";
        }
        String sanitized = uuid.replaceAll("[\r\n\t]", "");

        return sanitized.length() > 50 ? sanitized.substring(0, 50) + "..." : sanitized; // 로그에 너무 긴 UUID는 일부만 표시
    }

    private void handleConnect(StompHeaderAccessor accessor, Map<String, Object> sessionAttributes) {
        String uuid = accessor.getFirstNativeHeader(WebSocketConstants.HEADER_UUID);

        if (uuid == null || uuid.trim().isEmpty()) {
            log.warn("STOMP CONNECT 올바르지 않은 접근 시도: uuid :{}, 목적지: {}", uuid, accessor.getDestination());
            throw new IllegalArgumentException("STOMP CONNECT: uuid가 없거나 빈 문자열입니다. 연결이 거부되었습니다.");
        }

        if (!UUID_PATTERN.matcher(uuid).matches()) {
            log.warn("STOMP CONNECT 악의적인 접근 시도 (형식 위반): 전달된 UUID = {}", sanitizeForLog(uuid));
            throw new IllegalArgumentException("STOMP CONNECT: 유효하지 않은 UUID 형식입니다. 연결이 거부되었습니다.");
        }

        if (sessionAttributes == null) {
            throw new IllegalStateException("세션 속성 맵이 존재하지 않습니다. 연결이 거부되었습니다.");
        }

        sessionAttributes.put(WebSocketConstants.SESSION_ATTR_UUID, uuid);
        log.info("STOMP CONNECT: {} 연결됨", uuid);
    }

    private void handleDisconnect(Map<String, Object> sessionAttributes) {
        String disconnectUuid = (sessionAttributes != null && sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID) != null)
                ? (String) sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID)
                : WebSocketConstants.UNKNOWN_USER;
        log.info("STOMP DISCONNECTED: {}", disconnectUuid);
    }
}
