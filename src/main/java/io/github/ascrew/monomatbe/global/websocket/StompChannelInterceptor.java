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
        log.info("STOMP Message Intercepted: {}", message.getHeaders().get("simpMessageType")); // STOMP 메시지가 전송되기 전에 메시지 유형을 로그에 기록하여 디버깅에 도움을 줌

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && accessor.getCommand() != null) {
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            String uuid = (sessionAttributes != null) ? (String) sessionAttributes.get("uuid") : "UNKNOWN";

            StompCommand command = accessor.getCommand();

            //유저의 행동(Command)에 따라 감시 및 차단 로직
            switch (command) {
                case CONNECT:
                    log.info("STOMP CONNECTED: {}", uuid);
                    break;
                case SUBSCRIBE:
                    String subDestination = accessor.getDestination();
                    log.info("[STOMP SUBSCRIBE] 방 입장(구독) 시도 - UUID: {}, 목적지: {}", uuid, subDestination);
                    // log.info("STOMP SUBSCRIBE 올바르지 않은 접근 시도: uuid :{}, 목적지: {}", uuid, subDestination);
                    break;
                case SEND:
                    String sendDestination = accessor.getDestination();
                    log.info("메세지 발송: uuid :{}, 목적지: {}", uuid, sendDestination);
                    // 욕설 필터링 and 도배하는지 검사 로직 추가 예정

                    break;
                case UNSUBSCRIBE:
                    log.info("STOMP UNSUBSCRIBE: {}", uuid);
                    // "홍길동님이 퇴장" 로직 구현 예
                    break;
                case DISCONNECT:
                    log.info("STOMP DISCONNECTED: {}", uuid);
                    // redis에서 유저 정보 지우는 로직 추가
                    break;

                default:
                    break;
            }
        }
        return message; // 메시지를 그대로 반환하여 STOMP 메시지 처리를 계속 진행
    }
}



