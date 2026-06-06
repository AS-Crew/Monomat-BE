package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerInGameDisconnectEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerInGameReconnectEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
public class GameDisconnectManager {

    private final TaskScheduler taskScheduler;
    private final LobbyPlayerNicknameResolver nicknameResolver;
    private final SimpMessagingTemplate messagingTemplate;
    private final JsonMapper pubSubJsonMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RedisPublisher redisPublisher;
    private final StringRedisTemplate stringRedisTemplate;
    private final Duration disconnectGracePeriod;

    private final Map<String, ScheduledFuture<?>> disconnectTasks = new ConcurrentHashMap<>();

    public GameDisconnectManager(
            TaskScheduler taskScheduler,
            LobbyPlayerNicknameResolver nicknameResolver,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper,
            ApplicationEventPublisher eventPublisher,
            RedisPublisher redisPublisher,
            StringRedisTemplate stringRedisTemplate,
            @Value("${monomat.game.disconnect-grace-period:PT5S}") Duration disconnectGracePeriod
    ) {
        this.taskScheduler = taskScheduler;
        this.nicknameResolver = nicknameResolver;
        this.messagingTemplate = messagingTemplate;
        this.pubSubJsonMapper = pubSubJsonMapper;
        this.eventPublisher = eventPublisher;
        this.redisPublisher = redisPublisher;
        this.stringRedisTemplate = stringRedisTemplate;
        this.disconnectGracePeriod = disconnectGracePeriod;
    }

    @EventListener
    public void handleInGameDisconnect(PlayerInGameDisconnectEvent event) {
        String lobbyCode = event.lobbyCode();
        String userIdentifier = event.userIdentifier();
        String key = getTaskKey(lobbyCode, userIdentifier);

        log.info("[GameDisconnectManager] 인게임 이탈 감지 - 로비: {}, 식별자: {}", lobbyCode, userIdentifier);

        // 1. 기존 대기 작업이 있다면 취소 및 Redis 토큰 제거
        String tokenKey = RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier);
        stringRedisTemplate.delete(tokenKey);
        ScheduledFuture<?> existing = disconnectTasks.remove(key);
        if (existing != null) {
            existing.cancel(false);
        }

        // 2. 이탈 안내 시스템 메시지 브로드캐스트
        String nickname = resolveNickname(userIdentifier);
        broadcastSystemMessage(lobbyCode, userIdentifier, String.format("%s님이 이탈하셨습니다. 재접속을 대기합니다.", nickname));

        // 3. 고유 토큰 생성하여 Redis에 저장 (유예 기간 + 10초 여유를 주어 스케줄러 실행 시점까지 유효하도록 설정)
        String tokenId = java.util.UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(tokenKey, tokenId, disconnectGracePeriod.plusSeconds(10));

        // 4. 유예 기간 후 영구 퇴장 처리 스케줄링
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> executePermanentLeave(lobbyCode, userIdentifier, tokenId),
                Instant.now().plus(disconnectGracePeriod)
        );
        disconnectTasks.put(key, future);
    }

    @EventListener
    public void handleInGameReconnect(PlayerInGameReconnectEvent event) {
        cancelDisconnectTask(event.lobbyCode(), event.userIdentifier());
    }

    public void cancelDisconnectTask(String lobbyCode, String userIdentifier) {
        String key = getTaskKey(lobbyCode, userIdentifier);
        
        // Redis 토큰 제거
        String tokenKey = RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier);
        stringRedisTemplate.delete(tokenKey);

        ScheduledFuture<?> future = disconnectTasks.remove(key);
        if (future != null) {
            future.cancel(false);
            log.info("[GameDisconnectManager] 인게임 복귀 완료 - 타이머 취소. 로비: {}, 식별자: {}", lobbyCode, userIdentifier);

            // 복귀 안내 시스템 메시지 브로드캐스트
            String nickname = resolveNickname(userIdentifier);
            broadcastSystemMessage(lobbyCode, userIdentifier, String.format("%s님이 복귀하셨습니다.", nickname));
        }
    }

    @EventListener
    public void handleLobbyClosed(LobbyClosedEvent event) {
        String lobbyCode = event.lobbyCode();
        if (lobbyCode != null) {
            log.info("[GameDisconnectManager] 로비 폭파 감지 - 해당 로비의 이탈 복귀 타이머 제거. 로비: {}", lobbyCode);
            disconnectTasks.keySet().removeIf(key -> {
                if (key.startsWith(lobbyCode + ":")) {
                    String userIdentifier = key.substring(lobbyCode.length() + 1);
                    String tokenKey = RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier);
                    stringRedisTemplate.delete(tokenKey);

                    ScheduledFuture<?> future = disconnectTasks.get(key);
                    if (future != null) {
                        future.cancel(false);
                    }
                    return true;
                }
                return false;
            });
        }
    }

    private void executePermanentLeave(String lobbyCode, String userIdentifier, String tokenId) {
        String key = getTaskKey(lobbyCode, userIdentifier);
        String tokenKey = RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier);
        
        // Redis에서 활성 토큰을 조회하여 현재 스케줄 작업이 유효한지 검증
        String activeToken = stringRedisTemplate.opsForValue().get(tokenKey);
        if (activeToken == null || !activeToken.equals(tokenId)) {
            log.info("[GameDisconnectManager] 만료된 이탈 타이머 실행 무시 - 로비: {}, 식별자: {}, 토큰: {}", lobbyCode, userIdentifier, tokenId);
            return;
        }

        // 토큰이 일치하면 제거
        stringRedisTemplate.delete(tokenKey);
        disconnectTasks.remove(key);

        log.info("[GameDisconnectManager] 인게임 재접속 제한 시간 초과 - 영구 퇴장 처리 실행. 로비: {}, 식별자: {}", lobbyCode, userIdentifier);

        // 1. 퇴장 처리 이벤트 발행 (LobbyLeaveEventHandler가 퇴장 Lua 실행 및 방장 위임/폭파 처리 담당)
        eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));

        // 2. 퇴장 안내 메시지 브로드캐스트 (LEAVE 타입) - 퇴장 처리가 안전하게 끝난 뒤 순차 전송
        broadcastLeaveMessage(lobbyCode, userIdentifier);
    }

    private void broadcastSystemMessage(String lobbyCode, String sender, String content) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.SYSTEM)
                .roomId(lobbyCode)
                .sender(sender)
                .content(content)
                .timestamp(LocalDateTime.now().toString())
                .build();
        sendChatMessage(lobbyCode, message);
    }

    private void broadcastLeaveMessage(String lobbyCode, String sender) {
        String nickname = resolveNickname(sender);
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.LEAVE)
                .roomId(lobbyCode)
                .sender(sender)
                .content(String.format("%s님이 퇴장하셨습니다.", nickname))
                .timestamp(LocalDateTime.now().toString())
                .build();
        sendChatMessage(lobbyCode, message);
    }

    private String resolveNickname(String userIdentifier) {
        Map<String, String> resolved = nicknameResolver.resolveNicknameMap(java.util.List.of(userIdentifier));
        return resolved.getOrDefault(userIdentifier, nicknameResolver.fallbackNickname(userIdentifier));
    }

    private void sendChatMessage(String lobbyCode, ChatMessageDto message) {
        boolean published = redisPublisher.publish(
                StompDestinations.subscribeLobbyChat(lobbyCode),
                message
        );

        if (!published) {
            log.error("[GameDisconnectManager] 시스템 메시지 Pub/Sub 발행 실패 - 로컬 WebSocket fallback 전송. 로비: {}, sender: {}",
                    lobbyCode, message.getSender());
            try {
                String payload = pubSubJsonMapper.writeValueAsString(message);
                messagingTemplate.convertAndSend(
                        StompDestinations.subscribeLobbyChat(lobbyCode),
                        payload
                );
            } catch (Exception e) {
                log.error("[GameDisconnectManager] 시스템 메시지 직접 전송 실패 - 로비: {}, sender: {}", lobbyCode, message.getSender(), e);
            }
        }
    }

    private String getTaskKey(String lobbyCode, String userIdentifier) {
        return lobbyCode + ":" + userIdentifier;
    }
}
