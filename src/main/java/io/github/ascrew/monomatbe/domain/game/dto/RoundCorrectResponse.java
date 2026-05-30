package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

/**
 * 정답을 맞춘 플레이어에게 개인적으로 전송할 성공 응답 DTO.
 */
@Builder
public record RoundCorrectResponse(
        String type, // "ROUND_CORRECT"
        Integer roundNo,
        boolean isFuzzy,
        String message
) {}
