package io.github.ascrew.monomatbe.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && accessor.getCommand() != null) {
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

            StompCommand command = accessor.getCommand();

            //유저의 행동(Command)에 따라 감시 및 차단 로직
            switch (command) {
                case CONNECT:
                    // 최초 연결 시 헤더에서 uuid 추출
                    String uuid = accessor.getFirstNativeHeader("uuid");

                    // 검증 로직: uuid가 null이거나 빈 문자열인 경우, 연결을 차단
                    if (uuid == null || uuid.trim().isEmpty()) {
                        log.warn("STOMP CONNECT 올바르지 않은 접근 시도: uuid :{}, 목적지: {}", uuid, accessor.getDestination());
                        throw new IllegalArgumentException("STOMP CONNECT: uuid가 없거나 빈 문자열입니다. 연결이 거부되었습니다.");
                    }
                    // 검증 이후 sub나 send에 꺼내 쓸 수 있도록 세션에 uuid 저장
                    if (sessionAttributes != null){
                        sessionAttributes.put("uuid", uuid); // 세션 속성에 uuid 저장
                    }

                    log.info("STOMP CONNECT: {} 연결됨", uuid);

                    break;

                case SUBSCRIBE:
                case SEND:
                case UNSUBSCRIBE:
                    validateSession(accessor, sessionAttributes); // 세션 검증 메서드 호출
                    break;
                case DISCONNECT:
                    String disconnectUuid = (sessionAttributes != null) ? (String) sessionAttributes.get("uuid") : "UNKNOWN";

                    log.info("STOMP DISCONNECTED: {}", disconnectUuid);
                    break;

                default:
                    break;
            }
        }
        return message; // 메시지를 그대로 반환하여 STOMP 메시지 처리를 계속 진행
    }

    private void validateSession(StompHeaderAccessor accessor, Map<String, Object> sessionAttributes) {
        String uuid = (sessionAttributes != null) ? (String) sessionAttributes.get("uuid") : null;

        if (uuid == null) {
            log.warn("[{} 차단] 인증되지 않은 세션 접근", accessor.getCommand());
            throw new IllegalStateException("세션 인증 정보가 존재하지 않습니다.");
        }

        // 정상 로직 수행 (로그 기록 등)
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            if (destination != null && destination.startsWith("/topic/lobby/")) {
                String roomId = destination.replace("/topic/lobby/", "");

                if (sessionAttributes != null) {
                    sessionAttributes.put("roomId", roomId); // 세션 속성에 roomId 저장
                }
            }
            log.info("[SUBSCRIBE] 방 입장 - UUID: {}, Destination: {}", uuid, destination);
            // 특정 방에 대한 인가 검사 로직 추가해야함
        } else if (accessor.getCommand() == StompCommand.SEND) {
            log.info("[SEND] 메시지 발송 - UUID: {}, Destination: {}", uuid, accessor.getDestination());
            // 도배 방지 만들꺼면 로직 추가
        }else if (accessor.getCommand() == StompCommand.UNSUBSCRIBE) {
            log.info("[UNSUBSCRIBE] 방 퇴장 - UUID: {}", uuid);
            // UNSUBSCRIBE는 일반적으로 추가 차단 로직 없이 로그만 남깁니다.
        }
    }

}



