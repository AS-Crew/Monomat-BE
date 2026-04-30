package io.github.ascrew.monomatbe.messaging.dto;

import lombok.Builder;

// DTO 레코드 생성
@Builder
public record ChatMessageDto (
    MessageType type,
    String roomId,      // 방 번호
    String sender,      // 메시지 보낸 사람
    String content,     // 메시지 내용
    String timestamp   // 메시지 전송 시간
) {
    public enum MessageType {
        CHAT, ANSWER, ENTER, LEAVE
    }
}

