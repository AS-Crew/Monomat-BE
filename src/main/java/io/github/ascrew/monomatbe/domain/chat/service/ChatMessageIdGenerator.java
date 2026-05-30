package io.github.ascrew.monomatbe.domain.chat.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 채팅 메시지 식별자 생성기
 *
 * [설계 이유]
 * messageId 생성 책임을 ChatService에서 분리한다.
 * 이후 UUID에서 ULID/KSUID 등 시간 정렬 가능한 ID로 변경하더라도
 * 호출부를 수정하지 않도록 단일 컴포넌트로 관리한다.
 */
@Component
public class ChatMessageIdGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}