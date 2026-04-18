package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.repository.LobbyRedisKeys;
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

    // 웹소켓 세션과 유저/로비 정보 매핑 저장.
    // 컨트롤러나 인터셉터에서 유저가 로비에 최종 진입했을 때 호출함
    public void saveConnectionInfo(String sessionId, String userId, String lobbyCode) {
        String key = LobbyRedisKeys.getConnectionKey(sessionId);
        Map<String, String> data = Map.of(
                "userId", userId,
                "lobbyCode", lobbyCode
        );
        redisTemplate.opsForHash().putAll(key, data);
        // 세션 매핑 정보는 최대 7일간 보존. 실제 웹소켓 연결이 유지되는 동안은 이 정보가 유효하도록 충분히 긴 TTL 설정
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    // 웹소켓 연결 종료 이벤트 처리
    // 비정상 종료를 포함한 모든 연결 해제 시 Redis 세션 정보를 바탕으로 퇴장 로직 실행
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        if (sessionId == null) return;

        String key = LobbyRedisKeys.getConnectionKey(sessionId);
        Map<Object, Object> connectionInfo = redisTemplate.opsForHash().entries(key);

        if (connectionInfo.isEmpty()) return;

        String userId = (String) connectionInfo.get("userId");
        String lobbyCode = (String) connectionInfo.get("lobbyCode");

        // 필요 시 주석 해제하여 사용
        // log.info("웹소켓 연결 종료 감지 - 세션: {}, 유저: {}, 로비: {}", sessionId, userId, lobbyCode);

        // 1. 로비 퇴장 비즈니스 로직 실행
        lobbyEventService.handlePlayerLeave(lobbyCode, userId);

        // 2. 세션 매핑 정보 삭제
        redisTemplate.delete(key);
    }
}
