/*
 * 로비 조회(Query) REST API를 처리하는 컨트롤러
 *
 * [책임]
 * - 공개 로비 목록 조회
 * - 로비 대기실 상세 조회
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Tag(name = "Lobby Query", description = "로비 조회 REST API")
@RestController
@RequestMapping("/api/lobbies")
@RequiredArgsConstructor
public class LobbyQueryController {

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_GET_PUBLIC_LOBBIES_REQUEST =
            "요청 수신: 공개 로비 목록 조회 [GET /api/lobbies]";
    private static final String LOG_GET_PUBLIC_LOBBIES_RESPONSE =
            "조회 완료: 공개 로비 {}개 반환";
    private static final String LOG_GET_LOBBY_DETAIL_REQUEST =
            "요청 수신: 로비 상세 조회 [GET /api/lobbies/{code}] - 코드: {}, 식별자: {}";
    private static final String LOG_GET_LOBBY_DETAIL_RESPONSE =
            "로비 상세 조회 완료 - 코드: {}, canStart: {}";

    private final LobbyQueryService lobbyQueryService;

    /**
     * 로비 상세 조회 API
     *
     * [용도]
     * 로비 대기실 화면에서 필요한 참여자 ready 상태와 canStart 값을 조회한다.
     *
     * [주의]
     * 실제 최신 참여자 상태는 WebSocket 구독/해제 흐름에 의해 Redis에서 관리된다.
     * 클라이언트는 REFRESH_LOBBY_INFO 신호를 받으면 이 API를 다시 호출하여 최신 상태를 동기화한다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     * @return 로비 상세 정보
     */
    @Operation(
            summary = "로비 상세 조회",
            description = "로비 참여자 ready 상태와 canStart 값을 포함한 대기실 상세 정보를 조회합니다."
    )
    @GetMapping("/{code}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LobbyDetailResponse> getLobbyDetail(
            @PathVariable String code,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_GET_LOBBY_DETAIL_REQUEST,
                code,
                principal != null ? principal.userIdentifier() : "null"
        );

        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(code, principal);

        log.info(LOG_GET_LOBBY_DETAIL_RESPONSE, code, response.canStart());

        return ResponseEntity.ok(response);
    }

    /**
     * 공개 로비 목록 조회 API
     *
     * Redis에서 직접 필터링하여 공개 로비만 반환한다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    @Operation(
            summary = "공개 로비 목록 조회",
            description = "Redis에서 공개 상태인 로비만 필터링하여 반환합니다."
    )
    @GetMapping
    public ResponseEntity<List<LobbyRedisDto>> getPublicLobbies() {
        log.info(LOG_GET_PUBLIC_LOBBIES_REQUEST);

        List<LobbyRedisDto> publicLobbies = lobbyQueryService.getPublicLobbies();

        log.info(LOG_GET_PUBLIC_LOBBIES_RESPONSE, publicLobbies.size());

        return ResponseEntity.ok(publicLobbies);
    }
}