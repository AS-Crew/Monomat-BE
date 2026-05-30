package io.github.ascrew.monomatbe.domain.chat.controller;

import io.github.ascrew.monomatbe.domain.chat.service.LobbyRecentChatQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 로비 최근 채팅 조회 REST API 컨트롤러
 *
 * [책임]
 * - 최근 로비 채팅 조회 요청을 수신한다.
 * - 인증 주체를 확인한다.
 * - 실제 조회/권한 검증은 LobbyRecentChatQueryService에 위임한다.
 *
 * [주의]
 * 이 컨트롤러는 WebSocket 채팅 송신을 처리하지 않는다.
 * WebSocket 송신은 ChatController가 담당하고,
 * 이 컨트롤러는 새로고침/늦은 입장 시 최근 채팅 복원용 REST 조회만 담당한다.
 */
@Tag(name = "Lobby Recent Chat", description = "로비 최근 채팅 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lobbies/{code}/chats")
public class LobbyRecentChatController {

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";

    private final LobbyRecentChatQueryService lobbyRecentChatQueryService;

    /**
     * 로비 최근 채팅 메시지를 조회한다.
     *
     * [정책]
     * - JWT 인증이 필요하다.
     * - 현재 로비 참여자만 조회할 수 있다.
     * - 강퇴된 사용자는 조회할 수 없다.
     * - Redis 장애 시 서비스 계층에서 빈 목록을 반환한다.
     *
     * @param code 로비 초대 코드
     * @param principal JWT 인증 주체
     * @return 오래된 메시지부터 최신 메시지 순서의 최근 채팅 목록
     */
    @Operation(
            summary = "로비 최근 채팅 조회",
            description = """
                    로비 입장 또는 새로고침 시 Redis에 저장된 최근 로비 채팅 메시지를 조회합니다.
                    현재 로비 참여자만 조회할 수 있으며, 강퇴된 사용자는 조회할 수 없습니다.
                    """
    )
    @GetMapping("/recent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ChatMessageDto>> getRecentLobbyChats(
            @PathVariable String code,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        if (principal == null || principal.userIdentifier() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        List<ChatMessageDto> response = lobbyRecentChatQueryService.getRecentMessages(
                code,
                principal.userIdentifier()
        );

        return ResponseEntity.ok(response);
    }
}