package io.github.ascrew.monomatbe.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// DTO 레코드 생성
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
    private MessageType type;
    private String roomId;      // 방 번호
    private String sender;      // 메시지 보낸 사람
    private String content;     // 메시지 내용
    private String timestamp;   // 메시지 전송 시간

    public enum MessageType {
        CHAT, ANSWER, ENTER, LEAVE
    }
}
