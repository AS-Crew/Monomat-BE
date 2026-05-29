package io.github.ascrew.monomatbe.domain.auth.dto;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 관리자 금칙어 응답 DTO
 */
@Builder
public record ForbiddenNicknameResponse(
        Long id,
        String word,
        String normalizedWord,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ForbiddenNicknameResponse from(ForbiddenNicknameWord forbiddenNicknameWord) {
        return ForbiddenNicknameResponse.builder()
                .id(forbiddenNicknameWord.getId())
                .word(forbiddenNicknameWord.getWord())
                .normalizedWord(forbiddenNicknameWord.getNormalizedWord())
                .createdAt(forbiddenNicknameWord.getCreatedAt())
                .updatedAt(forbiddenNicknameWord.getUpdatedAt())
                .build();
    }
}