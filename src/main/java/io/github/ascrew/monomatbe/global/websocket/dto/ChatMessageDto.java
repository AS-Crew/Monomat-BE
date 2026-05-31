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
     * 서버가 생성한 채팅 메시지 식별자
     *
     * [용도]
     * - 로비 최근 채팅 Redis List에서 특정 메시지를 찾는다.
     * - 채팅 메시지 신고 API의 path variable로 사용한다.
     * - 클라이언트가 보낸 값은 신뢰하지 않고 서버에서 새로 생성한다.
     */
    private String messageId;

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
     * [하위 호환성]
     * 기존 FE와 WebSocket 계약에서 sender는 userIdentifier로 사용되고 있으므로 유지한다.
     *
     * [주의]
     * 클라이언트가 보낸 sender 값은 신뢰하지 않는다.
     * 서버에서 STOMP 세션에 저장된 userIdentifier로 덮어쓴다.
     */
    private String sender;

    /*
     * 발신자 users.id
     *
     * [용도]
     * - 채팅 메시지 신고 시 sender 스냅샷으로 저장한다.
     * - 자기 자신의 메시지 신고 차단에 사용한다.
     */
    private Long senderId;

    /*
     * 발신자 표시 닉네임
     *
     * [용도]
     * - 최근 채팅 복원 UI
     * - 채팅 메시지 신고 스냅샷
     */
    private String senderNickname;

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
     * [하위 호환성]
     * 기존 FE와 WebSocket 계약에서 timestamp를 사용하고 있으므로 유지한다.
     * 신규 코드에서는 sentAt과 동일한 서버 생성 UTC ISO-8601 문자열을 저장한다.
     */
    private String timestamp;

    /*
     * 메시지 발신 시각
     *
     * [용도]
     * - 채팅 메시지 신고 스냅샷의 sentAt으로 저장한다.
     * - timestamp보다 의미가 명확한 필드명으로 신규 계약에서 사용한다.
     */
    private String sentAt;

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