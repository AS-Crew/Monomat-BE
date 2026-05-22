/*
 * 로비 명령(Command) REST API를 처리하는 컨트롤러
 *
 * [책임]
 * - 로비 생성
 * - 초대 코드 기반 로비 입장 사전 검증
 * - 로비 참여자 ready 상태 변경
 * - 로비 게임 시작
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyMapRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyReadyRequest;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyCreateService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyJoinService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyMapUpdateService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyReadyService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyStartService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Lobby Command", description = "로비 명령 REST API")
@RestController
@RequestMapping("/api/lobbies")
@RequiredArgsConstructor
public class LobbyCommandController {

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_CREATE_LOBBY_REQUEST =
            "요청 수신: 로비 생성 [POST /api/lobbies] - 방장: {}";
    private static final String LOG_CREATE_LOBBY_RESPONSE =
            "로비 생성 응답 - 코드: {}";
    private static final String LOG_JOIN_LOBBY_REQUEST =
            "요청 수신: 로비 입장 [POST /api/lobbies/join] - 초대 코드: {}, 식별자: {}";
    private static final String LOG_JOIN_LOBBY_RESPONSE =
            "로비 입장 사전 검증 완료 - 초대 코드: {}";
    private static final String LOG_UPDATE_READY_REQUEST =
            "요청 수신: 로비 준비 상태 변경 [PATCH /api/lobbies/{code}/ready] - 코드: {}, 식별자: {}, ready: {}";
    private static final String LOG_UPDATE_READY_RESPONSE =
            "로비 준비 상태 변경 완료 - 코드: {}";
    private static final String LOG_START_LOBBY_REQUEST =
            "요청 수신: 게임 시작 [POST /api/lobbies/{code}/start] - 코드: {}, 식별자: {}";
    private static final String LOG_START_LOBBY_RESPONSE =
            "게임 시작 처리 완료 - 코드: {}";
    private static final String LOG_UPDATE_MAP_REQUEST =
            "요청 수신: 로비 맵 변경 [PATCH /api/lobbies/{code}/map] - 코드: {}, 식별자: {}, mapId: {}";
    private static final String LOG_UPDATE_MAP_RESPONSE =
            "로비 맵 변경 완료 - 코드: {}";

    private final LobbyCreateService lobbyCreateService;
    private final LobbyJoinService lobbyJoinService;
    private final LobbyReadyService lobbyReadyService;
    private final LobbyStartService lobbyStartService;
    private final LobbyMapUpdateService lobbyMapUpdateService;

    /**
     * 로비 생성 API
     *
     * [인증]
     * JWT Access Token이 필요하다.
     * @AuthenticationPrincipal로 CustomPrincipal을 주입받아
     * userId(DB용)와 userIdentifier(Redis용)를 서비스로 전달한다.
     *
     * [권한]
     * 게스트(GUEST)와 정식 회원(REGISTERED) 모두 로비를 생성할 수 있다.
     *
     * [계약 유지]
     * 기존 LobbyController의 POST /api/lobbies path와 요청/응답 DTO를 그대로 유지한다.
     *
     * @param request   로비 생성 요청 DTO
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

        CreateLobbyResponse response = lobbyCreateService.createLobby(request, principal);

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
     * @param request   로비 입장 요청 DTO
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

        JoinLobbyResponse response = lobbyJoinService.joinLobby(request.inviteCode(), principal);

        log.info(LOG_JOIN_LOBBY_RESPONSE, response.inviteCode());

        return ResponseEntity.ok(response);
    }

    /**
     * 로비 참여자의 준비 상태 변경 API
     *
     * [정책]
     * - JWT 인증이 필요하다.
     * - 로비 참여자만 준비 상태를 변경할 수 있다.
     * - 방장은 ready 대상에서 제외하고 시작 버튼만 사용한다.
     * - 준비 상태 변경 후 로비 내부 refresh 신호를 전송한다.
     *
     * @param code      로비 초대 코드
     * @param request   준비 상태 변경 요청 DTO
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

        lobbyReadyService.updateReadyStatus(code, request, principal);

        log.info(LOG_UPDATE_READY_RESPONSE, code);

        return ResponseEntity.noContent().build();
    }

    /**
     * 로비 게임 시작 API
     *
     * [정책]
     * - JWT 인증이 필요하다.
     * - 방장만 게임을 시작할 수 있다.
     * - 로비 상태가 WAITING일 때만 시작할 수 있다.
     * - 유효한 맵이 선택되어 있어야 한다.
     * - 맵의 문제 수가 설정된 라운드 수 이상이어야 한다.
     * - 방장을 제외한 모든 참여자가 ready 상태여야 한다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     * @return 204 No Content
     */
    @Operation(
            summary = "로비 게임 시작",
            description = """
                시작 조건을 최종 검증하고 로비 상태를 PLAYING으로 변경한 뒤 게임 시작 이벤트를 브로드캐스트합니다.
                로비 상세 응답의 canStart는 조회 시점의 버튼 활성화 기준이며,
                실제 시작 가능 여부는 이 API에서 Redis Lua로 최종 검증됩니다.
                따라서 canStart=true 이후에도 참여자 퇴장, ready 해제, 상태 변경이 발생하면 409 Conflict가 반환될 수 있습니다.
                """
    )
    @PostMapping("/{code}/start")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> startLobbyGame(
            @PathVariable String code,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_START_LOBBY_REQUEST,
                code,
                principal != null ? principal.userIdentifier() : "null"
        );

        lobbyStartService.startLobbyGame(code, principal);

        log.info(LOG_START_LOBBY_RESPONSE, code);

        return ResponseEntity.noContent().build();
    }

    /**
     * 로비 대기실 맵 변경 API
     *
     * [정책]
     * - JWT 인증이 필요하다.
     * - 방장만 맵을 변경할 수 있다.
     * - 로비 상태가 WAITING일 때만 변경할 수 있다.
     * - 맵 유효성(존재, 삭제, 접근 권한)은 서비스에서 검증한다.
     *
     * @param code      로비 초대 코드
     * @param request   맵 변경 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 204 No Content
     */
    @Operation(
            summary = "로비 맵 변경",
            description = "방장이 대기실에서 게임에 사용할 맵을 변경합니다. JWT 인증이 필요합니다."
    )
    @PatchMapping("/{code}/map")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> updateLobbyMap(
            @PathVariable String code,
            @Valid @RequestBody UpdateLobbyMapRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_UPDATE_MAP_REQUEST,
                code,
                principal != null ? principal.userIdentifier() : "null",
                request.mapId()
        );

        lobbyMapUpdateService.updateMap(code, request, principal);

        log.info(LOG_UPDATE_MAP_RESPONSE, code);

        return ResponseEntity.noContent().build();
    }
}