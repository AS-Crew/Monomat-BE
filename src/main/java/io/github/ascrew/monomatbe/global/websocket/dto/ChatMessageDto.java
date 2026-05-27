/*
 * WebSocket 채팅 메시지 전송에 사용되는 DTO
 * Redis Pub/Sub을 통해 직렬화/역직렬화되므로 @NoArgsConstructor가 필수적이다.
 *
 * [global/websocket/dto에 위치하는 이유]
 * 채팅 도메인 전용 객체가 아닌 WebSocket 통신 전반에서 사용되는 메시지 포맷입니다.
 * domain/chat/dto에 두면 global의 RedisPublisher, RedisSubscriber, WebSocketEventListener가
 * domain을 역참조하는 의존 방향 역전이 발생합니다.
 */
package io.github.ascrew.monomatbe.global.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {

    /*
     * 메시지 유형
     *
     * FE는 이 값을 기준으로 일반 채팅, 시스템 메시지, 입장/퇴장/강퇴,
     * ready 변경, 방장 변경 UI를 분기 처리한다.
     */
    private MessageType type;

    /*
     * 수신 대상 방 코드
     *
     * - 전체 채팅: "global"
     * - 로비 채팅: lobby inviteCode
     */
    private String roomId;

    /*
     * 발신자 식별자
     *
     * 클라이언트가 보낸 sender 값은 신뢰하지 않는다.
     * ChatService에서 STOMP 세션에 저장된 userIdentifier로 덮어쓴다.
     */
    private String sender;

    /*
     * 메시지 본문
     *
     * 일반 채팅에서는 사용자가 입력한 메시지이고,
     * 시스템 메시지에서는 서버가 생성한 안내 문구다.
     */
    private String content;

    /*
     * 메시지 발신 시각
     *
     * 클라이언트 timestamp는 신뢰하지 않고,
     * 서버에서 생성한 시각으로 덮어쓰는 방향으로 후속 단계에서 정리한다.
     */
    private String timestamp;

    /**
     * 로비 채팅 메시지 타입 표준
     *
     * - CHAT          : 사용자가 직접 입력한 일반 채팅 메시지
     * - SYSTEM        : 범용 시스템 안내 메시지
     * - ENTER         : 로비 입장 알림
     * - LEAVE         : 로비 퇴장 알림
     * - KICK          : 강퇴 알림
     * - READY_CHANGED : 준비 상태 변경 알림
     * - HOST_CHANGED  : 방장 변경 알림
     */
    public enum MessageType {
        CHAT,
        SYSTEM,
        ENTER,
        LEAVE,
        KICK,
        READY_CHANGED,
        HOST_CHANGED
    }
}