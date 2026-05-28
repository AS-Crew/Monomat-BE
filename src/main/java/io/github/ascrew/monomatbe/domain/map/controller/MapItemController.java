package io.github.ascrew.monomatbe.domain.map.controller;

import io.github.ascrew.monomatbe.domain.map.dto.CreateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapItemResponse;
import io.github.ascrew.monomatbe.domain.map.dto.ReorderMapItemsRequest;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.service.MapItemService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Map Item", description = "맵 문제(곡) CRUD API")
@RestController
@RequestMapping("/api/maps/{mapId}/items")
@RequiredArgsConstructor
public class MapItemController {

    private final MapItemService mapItemService;

    @Operation(summary = "맵 문제 목록 조회", description = "맵 소유자만 조회할 수 있습니다.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MapItemResponse>> getMapItems(
            @PathVariable Long mapId,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.ok(mapItemService.getMapItems(mapId, principal));
    }

    @Operation(summary = "맵 문제 생성", description = "맵 소유자만 생성할 수 있습니다.")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MapItemResponse> createMapItem(
            @PathVariable Long mapId,
            @Valid @RequestBody CreateMapItemRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapItemService.createMapItem(mapId, request, principal));
    }

    @Operation(summary = "맵 문제 수정", description = "맵 소유자만 수정할 수 있습니다.")
    @PutMapping("/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MapItemResponse> updateMapItem(
            @PathVariable Long mapId,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateMapItemRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        return ResponseEntity.ok(mapItemService.updateMapItem(mapId, itemId, request, principal));
    }

    @Operation(summary = "맵 문제 순서 재정렬", description = "맵 소유자만 변경할 수 있으며 활성 문제 전체의 ID를 원하는 순서대로 전달해야 합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "재정렬 성공"),
            @ApiResponse(responseCode = "400", description = "중복 ID / 누락 문제 / 다른 맵의 itemId / soft delete된 문제 포함"),
            @ApiResponse(responseCode = "401", description = "미인증"),
            @ApiResponse(responseCode = "403", description = "정식 회원(REGISTERED)이 아님 또는 맵 소유자가 아님"),
            @ApiResponse(responseCode = "404", description = "mapId에 해당하는 맵 없음"),
            @ApiResponse(responseCode = "409", description = "동시 요청으로 인한 순서 제약 충돌")
    })
    @PutMapping("/order")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> reorderMapItems(
            @PathVariable Long mapId,
            @Valid @RequestBody ReorderMapItemsRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        mapItemService.reorderMapItems(mapId, request, principal);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "맵 문제 삭제", description = "맵 소유자만 삭제할 수 있으며 soft delete 처리됩니다.")
    @DeleteMapping("/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteMapItem(
            @PathVariable Long mapId,
            @PathVariable Long itemId,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        mapItemService.deleteMapItem(mapId, itemId, principal);
        return ResponseEntity.noContent().build();
    }
}
