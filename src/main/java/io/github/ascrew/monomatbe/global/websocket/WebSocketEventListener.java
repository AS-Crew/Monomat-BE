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
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import io.github.ascrew.monomatbe.service.LobbyEventService;
import io.github.ascrew.monomatbe.global.constant.WebSocketConstants;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {


    private final RedisPublisher redisPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketMetric webSocketMetric; // WebSocketMetric을 주입받아 세션 수를 관리
    private final LobbyEventService lobbyEventService; // 비즈니스 로직(방장 위임 등) 서비스 주입

    private static final String USER_STATUS_KEY_PREFIX = "user_status:"; // Redis에서 사용자 상태를 저장할 때 사용할 키 접두사
    private static final String USER_ROOM_KEY_PREFIX = "user_room:"; // Redis에서 사용자가 참여한 방 정보를 저장할 때 사용할 키 접두사

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        String uuid = (sessionAttributes != null && sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID) != null)
                ? (String) sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID) : WebSocketConstants.UNKNOWN_USER;

        if (!WebSocketConstants.UNKNOWN_USER.equals(uuid)) {
            // Redis에 사용자 상태를 온라인으로 저장
            String userStatusKey = USER_STATUS_KEY_PREFIX + uuid;
            redisTemplate.opsForValue().set(userStatusKey, "ONLINE",2, TimeUnit.HOURS); // 2시간 동안 온라인 상태 유지, 필요에 따라 조정 가능
            webSocketMetric.increment(); //유저 접속시 증가
            log.info("Redis에 사용자 상태 저장: {} = ONLINE", userStatusKey);
        } else {
            log.warn("인증되지 않은 세션 연결 시도");
        }
        // 필요에 따라 클라이언트에게 알림 전송 가능
        // messagingTemplate.convertAndSend("/topic/connect", "새로운 WebSocket 연결이 생성되었습니다.");
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        // WebSocket 연결이 끊어졌을 때 실행되는 이벤트 리스너
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if(sessionAttributes != null){
            String uuid = (String) sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID);
            String roomId = (String) sessionAttributes.get(WebSocketConstants.SESSION_ATTR_ROOM_ID);

            if(uuid != null && !WebSocketConstants.UNKNOWN_USER.equals(uuid)){
                log.info("WebSocket 연결 끊김: uuid={}, roomId={}", uuid, roomId);

                // 공통 인프라 정리(Redis 상태, 지표 관리)
                redisTemplate.delete(USER_STATUS_KEY_PREFIX + uuid); // Redis에서 사용자 상태 제거
                webSocketMetric.decrement(); //유저 접속 끊김시 감소

                if(roomId != null){
                    log.info("퇴장 알림 방송 - 방번호: {}",roomId);
                    // 로비 이벤트 서비스의 퇴장 처리 로직을 트리거한다. (방장 위임, 인원 변동 등)
                    lobbyEventService.handlePlayerLeave(roomId, uuid);

                    // 퇴장 알림 발송 및 참여자 명단 정리
                    String leaveMessage = uuid + "님이 퇴장하셨습니다.";
                    ChatMessageDto chatMessageDto = ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.LEAVE)
                            .roomId(roomId)
                            .sender(uuid)
                            .content(leaveMessage)
                            .build();

                    // Redis에서 사용자가 참여한 방 정보 제거
                    redisPublisher.publish(WebSocketConstants.LOBBY_TOPIC_PREFIX + roomId, chatMessageDto);
                    redisTemplate.opsForSet().remove(USER_ROOM_KEY_PREFIX + roomId, uuid);
                }
            }
        }
    }

    // 유저가 채널을 구독 했을 때 redis set에 참여자 정보 추가
    @EventListener
    public void handleWebsocketSubscribeListener(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if(sessionAttributes != null){
            String uuid = (String) sessionAttributes.get(WebSocketConstants.SESSION_ATTR_UUID);
            String destination = accessor.getDestination();

            // 목적지가 로비 채팅방인 경우에만 동작
            if(destination != null && destination.startsWith(WebSocketConstants.LOBBY_TOPIC_PREFIX)){
                String roomId = destination.substring(WebSocketConstants.LOBBY_TOPIC_PREFIX.length()); // "/topic/lobby/" 접두사 제거하여 roomId 추출

                // 정상적으로 인증 된 유저만 redis 참여자 set에 추가
                if (uuid != null && !WebSocketConstants.UNKNOWN_USER.equals(uuid)) {
                    redisTemplate.opsForSet().add(USER_ROOM_KEY_PREFIX + roomId, uuid); // Redis에서 사용자가 참여한 방 정보 추가
                    log.info("Redis에 참여자 정보 추가: {} -> {}", roomId, uuid);
                }
            }
        }
    }
}
