package io.github.ascrew.monomatbe.domain.map.controller;

import io.github.ascrew.monomatbe.domain.map.dto.CreateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsRequest;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsResponse;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapPageResponse;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.MapSortType;
import io.github.ascrew.monomatbe.domain.map.service.MapManageService;
import io.github.ascrew.monomatbe.domain.map.service.MapService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Map", description = "맵 관련 REST API")
@RestController
@RequestMapping("/api/maps")
@RequiredArgsConstructor
public class MapController {

    private final MapService mapService;
    private final MapManageService mapManageService;

    @Operation(summary = "공개 맵 목록 조회")
    @GetMapping
    public ResponseEntity<MapPageResponse> getPublicMaps(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) MapCategory category,
            @RequestParam(required = false) MapSortType sort
    ) {
        return ResponseEntity.ok(mapService.getPublicMaps(page, size, keyword, category, sort));
    }

    @Operation(summary = "내 맵 목록 조회", description = "로그인한 사용자의 공개/비공개/공개 대기 맵을 검색/필터/정렬하여 조회합니다.")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MapPageResponse> getMyMaps(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) MapSortType sort,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.ok(mapService.getMyMaps(page, size, keyword, category, sort, principal));
    }

    @Operation(
            summary = "내 맵 단건 조회",
            description = "로그인한 정식 회원이 본인 소유의 공개/비공개/공개 대기 맵을 단건 조회합니다."
    )
    @GetMapping("/me/{mapId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MapDetailResponse> getMyMap(
            @PathVariable Long mapId,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.ok(mapService.getMyMap(mapId, principal));
    }

    @Operation(summary = "공개 맵 단건 조회")
    @GetMapping("/{mapId}")
    public ResponseEntity<MapDetailResponse> getPublicMap(@PathVariable Long mapId) {
        return ResponseEntity.ok(mapService.getPublicMap(mapId));
    }

    @Operation(summary = "맵 생성", description = "정식 회원(REGISTERED)만 생성 가능합니다.")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MapDetailResponse> createMap(
            @Valid @RequestBody CreateMapRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapService.createMap(request, principal));
    }

    @Operation(
            summary = "맵 생성 일괄 API",
            description = "맵 기본 정보와 문제 목록을 하나의 트랜잭션으로 생성합니다. 정식 회원(REGISTERED)만 생성 가능합니다."
    )
    @PostMapping("/with-items")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CreateMapWithItemsResponse> createMapWithItems(
            @Valid @RequestBody CreateMapWithItemsRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapManageService.createMapWithItems(request, principal));
    }

    @Operation(summary = "맵 수정", description = "맵 소유자만 수정 가능합니다.")
    @PutMapping("/{mapId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MapDetailResponse> updateMap(
            @PathVariable Long mapId,
            @Valid @RequestBody UpdateMapRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.ok(mapService.updateMap(mapId, request, principal));
    }

    @Operation(
            summary = "맵 관리 일괄 저장",
            description = "맵 기본 정보와 문제 목록의 생성/수정/삭제/순서 변경을 하나의 트랜잭션으로 처리합니다."
    )
    @PutMapping("/{mapId}/manage")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ManageMapResponse> updateManagedMap(
            @PathVariable Long mapId,
            @Valid @RequestBody ManageMapRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.ok(mapManageService.updateManagedMap(mapId, request, principal));
    }

    @Operation(summary = "맵 삭제", description = "맵 소유자만 삭제 가능하며 Soft Delete 처리됩니다.")
    @DeleteMapping("/{mapId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteMap(
            @PathVariable Long mapId,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        mapService.deleteMap(mapId, principal);
        return ResponseEntity.noContent().build();
    }
}