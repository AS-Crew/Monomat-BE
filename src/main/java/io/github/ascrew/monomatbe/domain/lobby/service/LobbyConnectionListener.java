/*
 * WebSocket 연결 해제 이벤트를 감지하여 로비 퇴장 처리를 트리거하는 컴포넌트.
 *
 * TODO: Commit #5(이중 리스너 통합)에서 WebSocketEventListener와 통합 예정.
 *       현재는 패키지 이동만 수행
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

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
     * WebSocket 연결 해제 이벤트 리스너.
     * Redis의 세션 매핑 정보를 기반으로 자동 퇴장 처리를 실행합니다.
     *
     * TODO: Commit #5에서 WebSocketEventListener로 통합 예정
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String wsSessionId = headerAccessor.getSessionId();

        if (wsSessionId == null) return;

        // Redis에서 세션에 매핑된 유저 식별자와 로비 코드를 조회
        String key = "ws:connection:" + wsSessionId;
        Map<Object, Object> connectionInfo = redisTemplate.opsForHash().entries(key);

        if (!connectionInfo.isEmpty()) {
            String userId = (String) connectionInfo.get("userId");
            String lobbyCode = (String) connectionInfo.get("lobbyCode");

            log.info("WebSocket 연결 종료 감지 - 세션: {}, 유저: {}, 로비: {}",
                    wsSessionId, userId, lobbyCode);

            // Lua 스크립트 기반 원자적 퇴장 처리
            lobbyEventService.handlePlayerLeave(lobbyCode, userId);

            // 처리 완료된 세션 매핑 정보 삭제
            redisTemplate.delete(key);
        }
    }

    /**
     * WebSocket 세션과 유저/로비 정보를 Redis에 매핑하여 저장합니다.
     * 유저가 로비에 입장하여 WebSocket 연결이 성공했을 때 호출합니다.
     *
     * @param wsSessionId WebSocket 고유 세션 ID
     * @param userId      사용자 식별자
     * @param lobbyCode   입장한 로비의 초대 코드
     */
    public void saveConnectionInfo(String wsSessionId, String userId, String lobbyCode) {
        String key = "ws:connection:" + wsSessionId;
        Map<String, String> data = Map.of(
                "userId", userId,
                "lobbyCode", lobbyCode
        );

        // TTL 설정으로 좀비 세션 데이터 방지
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, Duration.ofDays(1));
    }
}