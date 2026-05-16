/*
 * 초대 코드 기반 로비 입장 사전 검증 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 인증 주체 검증
 * - 초대 코드로 Redis 로비 정보 조회
 * - WAITING 상태 로비인지 검증
 * - 최대 인원 초과 여부 검증
 * - 이미 참여 중인 사용자의 재입장 허용
 *
 * [중요]
 * 이 서비스는 실제 Redis 참여자 등록을 수행하지 않는다.
 *
 * 실제 참여자 등록은 클라이언트가 WebSocket에 연결한 뒤 /topic/lobby/{code} 채널을 SUBSCRIBE하는 시점에
 * StompChannelInterceptor에서 enter_lobby.lua를 통해 원자적으로 처리한다.
 *
 * 따라서 이 서비스는 REST API 단계의 입장 가능 여부 사전 검증만 담당한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyJoinService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에는 입장할 수 없습니다.";
    private static final String ERROR_LOBBY_FULL =
            "최대 인원에 도달한 로비입니다.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_JOIN_LOBBY_REQUEST =
            "로비 입장 요청 - 초대 코드: {}, 식별자: {}";
    private static final String LOG_JOIN_LOBBY_SUCCESS =
            "로비 입장 사전 검증 통과 - 초대 코드: {}, 식별자: {}, 현재 인원: {}/{}";

    // =========================================================
    // 비즈니스 규칙 상수
    // =========================================================

    /**
     * 입장 가능한 로비 상태
     *
     * PLAYING, FINISHED 상태의 로비에는 입장할 수 없다.
     * Redis Hash에서 읽은 status 문자열과 비교하므로 name() 값으로 관리한다.
     */
    private static final String LOBBY_STATUS_WAITING = LobbyStatus.WAITING.name();

    private final LobbyRepository lobbyRepository;

    /**
     * 초대 코드 기반 로비 입장 사전 검증을 수행하고 로비 정보를 반환한다.
     *
     * [책임 범위]
     * 이 메서드는 입장 가능 여부만 검증합니다.
     * 실제 Redis 참여자 등록은 WebSocket SUBSCRIBE 시점에 enter_lobby.lua로 처리된다.
     *
     * [검증 순서]
     * 1. principal 및 userId 검증
     * 2. Redis에서 초대 코드 기반 로비 정보 조회
     * 3. 로비 상태가 WAITING인지 확인
     * 4. 이미 참여 중인지 확인
     * 5. 신규 입장자라면 최대 인원 초과 여부 확인
     *
     * [이미 참여 중인 사용자 처리]
     * 이미 participants Set에 존재하는 사용자는 currentPlayers가 maxPlayers 이상이어도
     * 재입장/새로고침 가능성을 고려해 통과시킨다.
     *
     * @param inviteCode 로비 초대 코드
     * @param principal  JWT에서 추출한 인증 주체
     * @return 로비 응답 DTO
     */
    public JoinLobbyResponse joinLobby(String inviteCode, CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            log.warn("로비 입장 요청 거부 - principal 또는 userId가 null. inviteCode: {}", inviteCode);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        log.info(LOG_JOIN_LOBBY_REQUEST, inviteCode, principal.userIdentifier());

        JoinLobbyResponse lobbyInfo = lobbyRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        if (!LOBBY_STATUS_WAITING.equals(lobbyInfo.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        boolean alreadyParticipant = lobbyRepository.isParticipant(
                inviteCode,
                principal.userIdentifier()
        );

        if (!alreadyParticipant && lobbyInfo.currentPlayers() >= lobbyInfo.maxPlayers()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_FULL);
        }

        log.info(
                LOG_JOIN_LOBBY_SUCCESS,
                inviteCode,
                principal.userIdentifier(),
                lobbyInfo.currentPlayers(),
                lobbyInfo.maxPlayers()
        );

        return lobbyInfo;
    }
}