package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerInGameDisconnectEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

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

    private final Map<String, ScheduledFuture<?>> disconnectTasks = new ConcurrentHashMap<>();

    public GameDisconnectManager(
            TaskScheduler taskScheduler,
            LobbyPlayerNicknameResolver nicknameResolver,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.taskScheduler = taskScheduler;
        this.nicknameResolver = nicknameResolver;
        this.messagingTemplate = messagingTemplate;
        this.pubSubJsonMapper = pubSubJsonMapper;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void handleInGameDisconnect(PlayerInGameDisconnectEvent event) {
        String lobbyCode = event.lobbyCode();
        String userIdentifier = event.userIdentifier();
        String key = getTaskKey(lobbyCode, userIdentifier);

        log.info("[GameDisconnectManager] 인게임 이탈 감지 - 로비: {}, 식별자: {}", lobbyCode, userIdentifier);

        // 기존 대기 작업이 있다면 취소
        ScheduledFuture<?> existing = disconnectTasks.remove(key);
        if (existing != null) {
            existing.cancel(false);
        }

        // 1. 이탈 안내 시스템 메시지 브로드캐스트
        String nickname = resolveNickname(userIdentifier);
        broadcastSystemMessage(lobbyCode, userIdentifier, String.format("%s님이 이탈하셨습니다. 재접속을 대기합니다.", nickname));

        // 2. 5초 후 영구 퇴장 처리 스케줄링
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> executePermanentLeave(lobbyCode, userIdentifier),
                Instant.now().plusSeconds(5)
        );
        disconnectTasks.put(key, future);
    }

    public void cancelDisconnectTask(String lobbyCode, String userIdentifier) {
        String key = getTaskKey(lobbyCode, userIdentifier);
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

    private void executePermanentLeave(String lobbyCode, String userIdentifier) {
        String key = getTaskKey(lobbyCode, userIdentifier);
        disconnectTasks.remove(key);

        log.info("[GameDisconnectManager] 인게임 재접속 제한 시간 초과 - 영구 퇴장 처리 실행. 로비: {}, 식별자: {}", lobbyCode, userIdentifier);

        // 1. 퇴장 안내 메시지 브로드캐스트 (LEAVE 타입)
        broadcastLeaveMessage(lobbyCode, userIdentifier);

        // 2. 퇴장 처리 이벤트 발행 (LobbyLeaveEventHandler가 퇴장 Lua 실행 및 방장 위임/폭파 처리 담당)
        eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));
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
        try {
            String payload = pubSubJsonMapper.writeValueAsString(message);
            messagingTemplate.convertAndSend(
                    StompDestinations.subscribeLobbyChat(lobbyCode),
                    payload
            );
        } catch (Exception e) {
            log.error("[GameDisconnectManager] 시스템 메시지 전송 실패 - 로비: {}, sender: {}", lobbyCode, message.getSender(), e);
        }
    }

    private String getTaskKey(String lobbyCode, String userIdentifier) {
        return lobbyCode + ":" + userIdentifier;
    }
}
