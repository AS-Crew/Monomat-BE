package io.github.ascrew.monomatbe.domain.chat.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 발신자 프로필 스냅샷
 *
 * [사용 목적]
 * 채팅 메시지 생성 시 senderId / senderNickname을 Redis 최근 채팅 payload에 포함하기 위해 사용한다.
 *
 * [주의]
 * userId와 nickname은 조회 실패 또는 과거 세션 정리 상태에 따라 null일 수 있다.
 * 채팅 전송 자체는 실시간성이 중요하므로, 프로필 조회 실패만으로 메시지 전송을 막지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSenderProfile {

    /**
     * Redis/WebSocket에서 사용하는 사용자 식별자
     */
    private String userIdentifier;

    /**
     * users.id
     */
    private Long userId;

    /**
     * 사용자 표시 닉네임
     */
    private String nickname;

    /**
     * 실제 사용자 프로필이 정상 조회된 상태인지 확인한다.
     *
     * unresolved profile은 채팅 전송 fallback 용도로만 사용하고,
     * Redis 캐시에 저장하지 않는다.
     *
     * @return userId와 nickname이 모두 유효하면 true
     */
    public boolean isResolved() {
        return userId != null
                && nickname != null
                && !nickname.isBlank();
    }

    public static ChatSenderProfile unresolved(String userIdentifier) {
        return ChatSenderProfile.builder()
                .userIdentifier(userIdentifier)
                .userId(null)
                .nickname(null)
                .build();
    }
}