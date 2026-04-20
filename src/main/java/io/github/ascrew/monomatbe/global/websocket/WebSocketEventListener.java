package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.service.RedisPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {


    private final RedisPublisher redisPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketMetric webSocketMetric; // WebSocketMetric을 주입받아 세션 수를 관리

    private final String USER_STATUS_KEY_PREFIX = "user_status:"; // Redis에서 사용자 상태를 저장할 때 사용할 키 접두사
    private final String USER_ROOM_KEY_PREFIX = "user_room:"; // Redis에서 사용자가 참여한 방 정보를 저장할 때 사용할 키 접두사

    // redis 템플릿 주입해야함

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        String uuid = (sessionAttributes != null) ? (String) sessionAttributes.get("uuid") : "UNKNOWN";

        if (!"UNKNOWN".equals(uuid)) {
            // Redis에 사용자 상태를 온라인으로 저장
            String userStatusKey = USER_STATUS_KEY_PREFIX + uuid;
            redisTemplate.opsForValue().set(userStatusKey, "ONLINE",2, TimeUnit.HOURS); // 2시간 동안 온라인 상태 유지, 필요에 따라 조정 가능
            log.info("Redis에 사용자 상태 저장: {} = ONLINE", userStatusKey);
        }

        // WebSocket 연결이 성공적으로 이루어졌을 때 실행되는 이벤트 리스너
        log.info("WebSocket 연결 성공: {}", uuid);
        webSocketMetric.increment(); //유저 접속시 증가

        // 필요에 따라 클라이언트에게 알림 전송 가능
        // messagingTemplate.convertAndSend("/topic/connect", "새로운 WebSocket 연결이 생성되었습니다.");
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        // WebSocket 연결이 끊어졌을 때 실행되는 이벤트 리스너
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if(sessionAttributes != null){
            String uuid = (String) sessionAttributes.get("uuid");
            String roomId = (String) sessionAttributes.get("roomId");

            if(uuid != null){
                log.info("WebSocket 연결 끊김: uuid={}, roomId={}", uuid, roomId);
                redisTemplate.delete(USER_STATUS_KEY_PREFIX + uuid); // Redis에서 사용자 상태 제거
                webSocketMetric.decrement(); //유저 접속 끊김시 감소
                if(roomId != null){
                    log.info("퇴장 알림 방송 - 방번호: {}",roomId);

                    String leaveMessage = uuid + "님이 퇴장하셨습니다.";
                    ChatMessageDto chatMessageDto = ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.LEAVE)
                            .roomId(roomId)
                            .sender(uuid)
                            .content(leaveMessage)
                            .build();
                    String topicUrl = "/topic/lobby/"+roomId;
                    redisPublisher.publish(topicUrl, chatMessageDto);
                    // redis에서 해당 유저가 참여한 방 정보 제거 로직 추가 필요
                    redisTemplate.opsForSet().remove(USER_ROOM_KEY_PREFIX + roomId, uuid); // Redis에서 사용자가 참여한 방 정보 제거
                }
            }
        }



    }
}
