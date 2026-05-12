/*
 * 로비 관련 HTTP REST API를 처리하는 컨트롤러.
 *
 * [책임]
 * 클라이언트의 명시적인 데이터 조회 요청을 수신하고
 * LobbyService에 위임한 뒤 응답을 반환합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyReadyRequest;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Tag(name = "Lobby", description = "로비 관련 REST API")
@RestController
@RequestMapping("/api/lobbies")
@RequiredArgsConstructor
public class LobbyController {

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_CREATE_LOBBY_REQUEST =
            "요청 수신: 로비 생성 [POST /api/lobbies] - 방장: {}";
    private static final String LOG_CREATE_LOBBY_RESPONSE =
            "로비 생성 응답 - 코드: {}";
    private static final String LOG_GET_PUBLIC_LOBBIES_REQUEST =
            "요청 수신: 공개 로비 목록 조회 [GET /api/lobbies]";
    private static final String LOG_GET_PUBLIC_LOBBIES_RESPONSE =
            "조회 완료: 공개 로비 {}개 반환";
    private static final String LOG_GET_LOBBY_DETAIL_REQUEST =
            "요청 수신: 로비 상세 조회 [GET /api/lobbies/{code}] - 코드: {}, 식별자: {}";
    private static final String LOG_GET_LOBBY_DETAIL_RESPONSE =
            "로비 상세 조회 완료 - 코드: {}, canStart: {}";
    private static final String LOG_JOIN_LOBBY_REQUEST =
            "요청 수신: 로비 입장 [POST /api/lobbies/join] - 초대 코드: {}, 식별자: {}";
    private static final String LOG_JOIN_LOBBY_RESPONSE =
            "로비 입장 사전 검증 완료 - 초대 코드: {}";
    private static final String LOG_UPDATE_READY_REQUEST =
            "요청 수신: 로비 준비 상태 변경 [PATCH /api/lobbies/{code}/ready] - 코드: {}, 식별자: {}, ready: {}";
    private static final String LOG_UPDATE_READY_RESPONSE =
            "로비 준비 상태 변경 완료 - 코드: {}";

    private final LobbyService lobbyService;

    /**
     * 로비 생성 API
     *
     * [인증]
     * JWT Access Token이 필요합니다.
     * @AuthenticationPrincipal로 CustomPrincipal을 주입받아
     * userId(DB용)와 userIdentifier(Redis용)를 서비스로 전달한다.
     *
     * [권한]
     * 게스트(GUEST)와 정식 회원(REGISTERED) 모두 로비를 생성할 수 있다.
     *
     * @param request   로비 생성 요청 DTO (@Valid 검증 적용)
     * @param principal JWT에서 추출한 인증 주체
     * @return 201 Created + 생성된 로비 정보
     */
    @Operation(
            summary = "로비 생성",
            description = "로비를 생성하고 6자리 초대 코드를 발급합니다. JWT 인증이 필요합니다."
    )
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CreateLobbyResponse> createLobby(
            @Valid @RequestBody CreateLobbyRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_CREATE_LOBBY_REQUEST,
                principal != null ? principal.userIdentifier() : "null"
        );

        CreateLobbyResponse response = lobbyService.createLobby(request, principal);

        log.info(LOG_CREATE_LOBBY_RESPONSE, response.inviteCode());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 초대 코드 기반 로비 입장 API
     *
     * [인증]
     * JWT Access Token이 필요하다.
     * 게스트도 로그인 후 발급받은 토큰으로 입장할 수 있다.
     *
     * [처리 흐름]
     * 이 API는 입장 허가 사전 검증만 수행한다.
     * 실제 참여자 등록은 클라이언트가 응답을 받은 뒤
     * WebSocket /topic/lobby/{inviteCode}를 구독하는 시점에 처리된다.
     *
     * 클라이언트 처리 순서:
     * 1. POST /api/lobbies/join 호출 -> 입장 가능 여부 확인
     * 2. WebSocket SUBSCRIBE /topic/lobby/{inviteCode} -> 실제 입장 처리
     *
     * [에러 응답]
     * - 404 Not Found : 존재하지 않는 초대 코드
     * - 409 Conflict : 게임 진행 중인 로비 또는 최대 인원 초과
     *
     * @param request 로비 입장 요청 DTO (초대 코드 포함, @Valid 검증 적용)
     * @param principal JWT에서 추출한 인증 주체
     * @return 200 OK + 로비 기본 정보
     */
    @Operation(
            summary = "초대 코드 기반 로비 입장",
            description = """
                    초대 코드로 로비 입장 가능 여부를 검증하고 로비 기본 정보를 반환합니다.
                    실제 참여자 등록은 이후 WebSocket 구독 시점에 처리됩니다.
                    JWT 인증이 필요합니다.
                    """
    )
    @PostMapping("/join")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JoinLobbyResponse> joinLobby(
            @Valid @RequestBody JoinLobbyRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_JOIN_LOBBY_REQUEST,
                request.inviteCode(),
                principal != null ? principal.userIdentifier() : "null"
        );

        JoinLobbyResponse response = lobbyService.joinLobby(request.inviteCode(), principal);

        log.info(LOG_JOIN_LOBBY_RESPONSE, response.inviteCode());

        return ResponseEntity.ok(response);
    }

    /**
     * 로비 참여자의 준비 상태 변경 API.
     *
     * [정책]
     * - JWT 인증이 필요하다.
     * - 로비 참여자만 준비 상태를 변경할 수 있다.
     * - 방장은 ready 대상에서 제외하고 시작 버튼만 사용한다.
     * - 준비 상태 변경 후 로비 내부 refresh 신호를 전송한다.
     *
     * @param code 로비 초대 코드
     * @param request 준비 상태 변경 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 204 No Content
     */
    @Operation(
            summary = "로비 준비 상태 변경",
            description = "로비 참여자의 ready 상태를 변경합니다. 방장은 ready 대상에서 제외됩니다."
    )
    @PatchMapping("/{code}/ready")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> updateReadyStatus(
            @PathVariable String code,
            @Valid @RequestBody UpdateLobbyReadyRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_UPDATE_READY_REQUEST,
                code,
                principal != null ? principal.userIdentifier() : "null",
                request.ready()
        );

        lobbyService.updateReadyStatus(code, request, principal);

        log.info(LOG_UPDATE_READY_RESPONSE, code);

        return ResponseEntity.noContent().build();
    }

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
     * @param code 로비 초대 코드
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

        LobbyDetailResponse response = lobbyService.getLobbyDetail(code, principal);

        log.info(LOG_GET_LOBBY_DETAIL_RESPONSE, code, response.canStart());

        return ResponseEntity.ok(response);
    }

    /**
     * 공개 로비 목록 조회 API.
     * Redis에서 직접 필터링하여 공개 로비만 반환합니다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    @Operation(summary = "공개 로비 목록 조회", description = "Redis에서 공개 상태인 로비만 필터링하여 반환합니다.")
    @GetMapping
    public ResponseEntity<List<LobbyRedisDto>> getPublicLobbies() {
        log.info(LOG_GET_PUBLIC_LOBBIES_REQUEST);

        List<LobbyRedisDto> publicLobbies = lobbyService.getPublicLobbies();

        log.info(LOG_GET_PUBLIC_LOBBIES_RESPONSE, publicLobbies.size());

        return ResponseEntity.ok(publicLobbies);
    }
}