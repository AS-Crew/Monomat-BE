package io.github.ascrew.monomatbe.domain.youtube.controller;

import io.github.ascrew.monomatbe.domain.youtube.dto.YoutubeValidateRequest;
import io.github.ascrew.monomatbe.domain.youtube.dto.YoutubeValidateResponse;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "YouTube", description = "YouTube URL 검증 API")
@RestController
@RequestMapping("/api/youtube")
@RequiredArgsConstructor
public class YoutubeController {

    private final YoutubeValidationService youtubeValidationService;

    @Operation(summary = "YouTube URL 유효성 검증", description = "정식 회원(REGISTERED)만 검증 가능합니다.")
    @PostMapping("/validate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<YoutubeValidateResponse> validate(
            @Valid @RequestBody YoutubeValidateRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        YoutubeMetadata metadata = youtubeValidationService.validateForAuthoring(principal, request.youtubeUrl());
        return ResponseEntity.ok(YoutubeValidateResponse.builder()
                .videoId(metadata.videoId())
                .title(metadata.title())
                .artist(metadata.artist())
                .thumbnailUrl(metadata.thumbnailUrl())
                .build());
    }
}
