package io.github.ascrew.monomatbe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyConnectionListener {

    private final StringRedisTemplate redisTemplate;
    private final LobbyEventService lobbyEventService;

    /**
     * 웹소켓 연결 해제 이벤트 리스너
     * Spring WebSocket이 발행하는 SessionDisconnectEvent를 감지하여
     * Redis에 저장된 세션 매핑 정보를 바탕으로 자동 퇴장 비즈니스 로직을 실행한다.
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        // 이벤트에서 세션 ID를 추출한다.
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        if (sessionId == null) return;

        // Redis에서 해당 세션에 묶인 유저 ID와 로비 코드를 조회한다.
        String key = "ws:connection:" + sessionId;
        Map<Object, Object> connectionInfo = redisTemplate.opsForHash().entries(key);

        if (!connectionInfo.isEmpty()) {
            String userId = (String) connectionInfo.get("userId");
            String lobbyCode = (String) connectionInfo.get("lobbyCode");

            log.info("웹소켓 연결 종료 감지 - 세션: {}, 유저: {}, 로비: {}", sessionId, userId, lobbyCode);

            // 1. Lua 스크립트 기반 원자적 퇴장 로직 트리거
            lobbyEventService.handlePlayerLeave(lobbyCode, userId);

            // 2, 처리가 완료된 세션 매핑 정보는 삭제하여 메모리를 관리한다.
            redisTemplate.delete(key);
        }
    }

    /**
     * 세션 매핑 정보 등록
     * 유저가 방에 입장하여 웹소켓 연결이 성공했을 때 호출한다.
     * * @param sessionId 웹소켓 고유 세션 ID
     * @param userId 유저 고유 ID
     * @param lobbyCode 진입한 로비의 초대 코드
     */
    public void saveConnectionInfo(String sessionId, String userId, String lobbyCode) {
        String key = "ws:connection:" + sessionId;
        Map<String, String> data = Map.of(
                "userId", userId,
                "lobbyCode", lobbyCode
        );

        // 정보를 Redis Hash로 저장하고, 만료 시간 (TTL)을 설정하여 좀비 세션 데이터를 방지한다.
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, Duration.ofDays(1));
    }
}