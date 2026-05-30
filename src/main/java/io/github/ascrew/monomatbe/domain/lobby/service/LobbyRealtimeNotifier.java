/*
 * 로비 실시간 알림 전용 컴포넌트
 *
 * [책임]
 * - 로비 목록 refresh 브로드캐스트
 * - 로비 내부 정보 refresh 브로드캐스트
 * - 게임 시작 이벤트 브로드캐스트
 * - KICK 시스템 메시지 생성 및 브로드캐스트
 * - READY_CHANGED 시스템 메시지 생성 및 브로드캐스트
 * - HOST_CHANGED 시스템 메시지 생성 및 브로드캐스트
 *
 * [설계 의도]
 * 기존 단일 로비 이벤트 서비스는 강퇴 유스케이스, 퇴장 이벤트 처리, STOMP 브로드캐스트를 함께 담당했다.
 * 이 클래스는 STOMP 알림 책임만 분리하여, 강퇴/퇴장/시작/ready 변경 서비스가
 * "무엇을 알릴지"만 결정하고 "어떻게 보낼지"는 이 컴포넌트에 위임하도록 만든다.
 *
 * [주의]
 * 기존 STOMP destination 계약은 변경하지 않는다.
 * FE가 이미 구독 중인 /topic 경로와 메시지 문자열을 그대로 유지한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyRealtimeNotifier {

    /*
     * 로비 코드 검증 패턴.
     * 기존 로비 이벤트 처리 흐름에서 사용하던 검증 규칙을 그대로 유지한다.
     */
    private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

    private static final String KICK_MESSAGE_FORMAT = "%s님이 강퇴되었습니다.";
    private static final String READY_CHANGED_MESSAGE_FORMAT = "%s님이 %s 상태로 변경했습니다.";
    private static final String HOST_CHANGED_MESSAGE_FORMAT = "%s님이 새로운 방장이 되었습니다.";

    private static final String READY_STATUS_READY = "준비 완료";
    private static final String READY_STATUS_NOT_READY = "준비 해제";

    private final SimpMessagingTemplate messagingTemplate;
    private final LobbyRepository lobbyRepository;

    @Qualifier("pubSubJsonMapper")
    private final JsonMapper pubSubJsonMapper;

    /**
     * 전역 로비 리스트 refresh 신호를 발행한다.
     *
     * [사용 시점]
     * - 로비 생성
     * - 로비 폭파
     *
     * [destination]
     * 기존 FE 계약을 유지하기 위해 StompDestinations 상수를 그대로 사용한다.
     */
    public void notifyLobbyListRefresh() {
        messagingTemplate.convertAndSend(
                StompDestinations.SUBSCRIBE_LOBBY_LIST_REFRESH,
                StompDestinations.MSG_REFRESH_LOBBY_LIST
        );
    }

    /**
     * 특정 로비 내부 정보 refresh 신호를 발행한다.
     *
     * [검증]
     * - 로비 코드 형식이 잘못된 경우 무시한다.
     * - 요청자 식별자가 없는 경우 무시한다.
     * - Redis에 로비가 없는 경우 무시한다.
     * - 요청자가 해당 로비 참여자가 아닌 경우 무시한다.
     *
     * [이유]
     * 이 메서드는 클라이언트가 직접 호출하는 STOMP update 경로에서도 사용된다.
     * 따라서 임의 사용자의 refresh 이벤트 남발을 막기 위해 참여자 검증을 유지한다.
     */
    public void notifyLobbyInfoRefresh(String code, String userIdentifier) {
        if (!isValidLobbyCode(code)) {
            return;
        }

        if (!StringUtils.hasText(userIdentifier)) {
            return;
        }

        if (!lobbyRepository.existsByCode(code)) {
            return;
        }

        if (!lobbyRepository.isParticipant(code, userIdentifier)) {
            return;
        }

        notifyLobbyInfoRefreshInternal(code);
    }

    /**
     * 서버 내부 로직에서 로비 내부 정보 refresh 신호를 발행한다.
     *
     * [사용 시점]
     * - leave_lobby.lua 처리 이후
     * - kick_lobby.lua 처리 이후
     * - DB commit 이후 game started refresh
     *
     * [차이점]
     * 서버 내부에서 이미 유스케이스 검증이 끝난 뒤 호출하므로 userIdentifier 참여자 검증을 요구하지 않는다.
     */
    public void notifyLobbyInfoRefresh(String code) {
        if (!isValidLobbyCode(code)) {
            return;
        }

        if (!lobbyRepository.existsByCode(code)) {
            return;
        }

        notifyLobbyInfoRefreshInternal(code);
    }

    /**
     * 로비 참여자에게 게임 시작 이벤트를 발행한다.
     *
     * [사용 목적]
     * FE가 대기실 화면에서 인게임 화면으로 전환할 수 있도록 알린다.
     */
    public void notifyGameStarted(String code) {
        if (!isValidLobbyCode(code)) {
            return;
        }

        if (!lobbyRepository.existsByCode(code)) {
            return;
        }

        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyGame(code),
                StompDestinations.MSG_GAME_STARTED
        );
    }

    /**
     * KICK 시스템 메시지를 로비 채팅 채널로 발행한다.
     *
     * [반환값]
     * - true: 직렬화 및 전송 성공
     * - false: 직렬화 또는 전송 실패
     *
     * [주의]
     * 강퇴 상태 변경은 Redis Lua에서 이미 완료된 상태다.
     * 메시지 발행 실패가 강퇴 자체를 롤백하면 오히려 상태 정합성이 더 나빠진다.
     * 따라서 실패 여부만 반환하고, 호출자가 관측 로그를 남긴다.
     */
    public boolean notifyKickMessage(String lobbyCode, String targetUserIdentifier) {
        return notifySystemChatMessage(
                lobbyCode,
                ChatMessageDto.MessageType.KICK,
                targetUserIdentifier,
                String.format(KICK_MESSAGE_FORMAT, targetUserIdentifier),
                "KICK"
        );
    }

    /**
     * READY_CHANGED 시스템 메시지를 로비 채팅 채널로 발행한다.
     *
     * [사용 시점]
     * 참여자가 ready 상태를 변경한 직후 호출한다.
     *
     * [주의]
     * ready 상태 변경은 Redis ready Set에 이미 반영된 상태다.
     * 메시지 발행 실패가 ready 상태 변경을 롤백해서는 안 되므로 boolean 결과만 반환한다.
     */
    public boolean notifyReadyChangedMessage(
            String lobbyCode,
            String userIdentifier,
            boolean ready
    ) {
        String readyStatus = ready ? READY_STATUS_READY : READY_STATUS_NOT_READY;

        return notifySystemChatMessage(
                lobbyCode,
                ChatMessageDto.MessageType.READY_CHANGED,
                userIdentifier,
                String.format(READY_CHANGED_MESSAGE_FORMAT, userIdentifier, readyStatus),
                "READY_CHANGED"
        );
    }

    /**
     * HOST_CHANGED 시스템 메시지를 로비 채팅 채널로 발행한다.
     *
     * [사용 시점]
     * 방장 퇴장으로 새 방장이 위임된 직후 호출한다.
     */
    public boolean notifyHostChangedMessage(
            String lobbyCode,
            String newHostIdentifier
    ) {
        return notifySystemChatMessage(
                lobbyCode,
                ChatMessageDto.MessageType.HOST_CHANGED,
                newHostIdentifier,
                String.format(HOST_CHANGED_MESSAGE_FORMAT, newHostIdentifier),
                "HOST_CHANGED"
        );
    }

    /**
     * 로비 채팅 채널로 시스템 메시지를 전송한다.
     *
     * [공통 계약]
     * - roomId: lobbyCode
     * - sender: 이벤트 주체 userIdentifier
     * - timestamp: 서버 시각
     *
     * [전송 방식]
     * 기존 KICK 메시지와 동일하게 SimpMessagingTemplate으로 직접 전송한다.
     * Redis Pub/Sub을 거치는 구조로 바꾸면 멀티 인스턴스 전파 일관성은 좋아지지만,
     * 이번 단계에서는 기존 destination/payload 계약을 유지하는 것을 우선한다.
     */
    private boolean notifySystemChatMessage(
            String lobbyCode,
            ChatMessageDto.MessageType type,
            String sender,
            String content,
            String context
    ) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(type)
                .roomId(lobbyCode)
                .sender(sender)
                .content(content)
                .timestamp(LocalDateTime.now().toString())
                .build();

        try {
            String payload = pubSubJsonMapper.writeValueAsString(message);

            messagingTemplate.convertAndSend(
                    StompDestinations.subscribeLobbyChat(lobbyCode),
                    payload
            );

            return true;
        } catch (Exception e) {
            log.error(
                    "{} 메시지 직렬화 또는 전송 실패 - lobbyCode: {}, sender: {}",
                    context,
                    lobbyCode,
                    sender,
                    e
            );
            return false;
        }
    }

    /**
     * 실제 refresh 전송만 담당하는 내부 메서드
     */
    private void notifyLobbyInfoRefreshInternal(String code) {
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(code),
                StompDestinations.MSG_REFRESH_LOBBY_INFO
        );
    }

    private boolean isValidLobbyCode(String code) {
        return StringUtils.hasText(code) && LOBBY_CODE_PATTERN.matcher(code).matches();
    }
}