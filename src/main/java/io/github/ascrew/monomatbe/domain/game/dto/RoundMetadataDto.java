package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

/**
 * 라운드 종료 시 클라이언트(FE)에 공개할 메타데이터 DTO.
 */
@Builder
public record RoundMetadataDto(
        String title,
        String artist,
        String answer,
        String thumbnailUrl
) {
}
