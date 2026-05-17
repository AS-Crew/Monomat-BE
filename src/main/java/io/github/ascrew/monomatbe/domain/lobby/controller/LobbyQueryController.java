/*
 * 로비 조회(Query) REST API를 처리하는 컨트롤러
 *
 * [책임]
 * - 공개 로비 목록 조회
 * - 로비 대기실 상세 조회
 *
 * [주의]
 * Controller는 HTTP 요청 파라미터를 수집하고, 요청 조건 객체로 변환한 뒤 Service에 위임하는 역할만 담당한다.
 * 검색/필터/정렬 같은 비즈니스 정책은 Service 계층에서 처리한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbySearchCondition;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
            "요청 수신: 공개 로비 목록 조회 [GET /api/lobbies] - keyword: {}, mapCategory: {}, sort: {}";
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
     * [현재 지원 조건]
     * - keyword : 로비 제목 검색어
     * - mapCategory : 선택된 맵 카테코리 필터
     * - sort : 정렬 기준
     *
     * [기본 정책]
     * - keyword가 없으면 제목 검색을 적용하지 않는다.
     * - mapCategory가 없으면 카테고리 필터를 적용하지 않는다.
     * - sort가 없으면 latest를 기본 정렬로 사용한다.
     *
     * [책임 경계]
     * Controller는 요청 파라미터를 LobbySearchCondition으로 변환하는 역할만 한다.
     * 실제 공개 로비 보장, WAITING 상태 필터, 검색, 정렬 정책은 LobbyQueryService에서 처리한다.
     *
     * @param keyword     로비 제목 검색어
     * @param mapCategory 맵 카테고리 필터 값. 예: K-POP, J-POP, POP
     * @param sort        정렬 기준. 예: latest, most_players, most_available
     * @return 조건에 맞는 공개 로비 목록
     */
    @Operation(
            summary = "공개 로비 목록 조회",
            description = """
                    공개 로비 목록을 조회합니다.
                    keyword로 로비 제목을 검색할 수 있고,
                    mapCategory로 선택된 맵 카테고리를 필터링할 수 있으며,
                    sort로 정렬 기준을 지정할 수 있습니다.
                    """
    )
    @GetMapping
    public ResponseEntity<List<LobbyRedisDto>> getPublicLobbies(
            @Parameter(description = "로비 제목 검색어")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "맵 카테고리 필터 값. 예: K-POP, J-POP, POP")
            @RequestParam(required = false) String mapCategory,

            @Parameter(description = "정렬 기준. latest, most_players, most_available")
            @RequestParam(required = false) String sort
    ) {
        log.info(LOG_GET_PUBLIC_LOBBIES_REQUEST, keyword, mapCategory, sort);

        LobbySearchCondition condition = LobbySearchCondition.of(
                keyword,
                mapCategory,
                sort
        );

        List<LobbyRedisDto> publicLobbies = lobbyQueryService.getPublicLobbies(condition);

        log.info(LOG_GET_PUBLIC_LOBBIES_RESPONSE, publicLobbies.size());

        return ResponseEntity.ok(publicLobbies);
    }
}