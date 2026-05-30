package io.github.ascrew.monomatbe.domain.game.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 인게임 전용 채팅 송신 DTO.
 * 라운드 번호를 포함하여 만료된 이전 라운드 정답 제출을 방지합니다.
 */
public record GameChatMessageDto(
        @NotNull(message = "라운드 번호는 필수입니다.")
        Integer roundNo,

        @NotBlank(message = "메시지 내용은 비어있을 수 없습니다.")
        @Size(max = 500, message = "메시지는 최대 500자까지 전송 가능합니다.")
        String content
) {}
