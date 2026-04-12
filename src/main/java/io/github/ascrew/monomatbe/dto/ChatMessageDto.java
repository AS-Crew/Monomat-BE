package io.github.ascrew.monomatbe.dto;



// DTO 레코드 생성
public record ChatMessageDto(
        MessageType type,
        String sender,
        String content,
        String timestamp
) {
    // 메시지 타입 정의
    public enum MessageType {
        CHAT, ANSWER, ENTER, LEAVE
    }
}