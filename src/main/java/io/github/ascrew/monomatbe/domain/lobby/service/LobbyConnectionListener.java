package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
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
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        // WebSocket 세션 ID (사용자 식별자와 다른 개념)
        String wsSessionId = accessor.getSessionId();

        if (wsSessionId == null) return;

        // Redis에서 wsSessionId에 매핑된 사용자 식별자와 로비 코드 조회
        Map<Object, Object> connectionInfo =
                redisTemplate.opsForHash().entries(RedisKeys.wsConnectionKey(wsSessionId));

        if (!connectionInfo.isEmpty()) {
            String userIdentifier = (String) connectionInfo.get("userId");
            String lobbyCode = (String) connectionInfo.get("lobbyCode");

            log.info("WebSocket 연결 종료 - wsSessionId: {}, 식별자: {}, 로비: {}",
                    wsSessionId, userIdentifier, lobbyCode);

            // Lua 스크립트 기반 원자적 퇴장 처리
            lobbyEventService.handlePlayerLeave(lobbyCode, userIdentifier);

            // 처리 완료된 세션 매핑 정보 삭제
            redisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }
    }

    /**
     * WebSocket 세션과 사용자/로비 정보를 Redis에 매핑하여 저장합니다.
     * 유저가 로비에 입장하여 WebSocket 연결이 성공했을 때 호출합니다.
     *
     * @param wsSessionId   WebSocket 고유 세션 ID
     * @param userIdentifier 사용자 식별자 (게스트 UUID or 회원 ID)
     * @param lobbyCode     입장한 로비의 초대 코드
     */
    public void saveConnectionInfo(String wsSessionId, String userIdentifier, String lobbyCode) {
        Map<String, String> data = Map.of(
                "userId", userIdentifier,
                "lobbyCode", lobbyCode
        );

        // TTL 설정으로 좀비 세션 데이터 방지
        redisTemplate.opsForHash().putAll(RedisKeys.wsConnectionKey(wsSessionId), data);
        redisTemplate.expire(RedisKeys.wsConnectionKey(wsSessionId), Duration.ofDays(1));
    }
}