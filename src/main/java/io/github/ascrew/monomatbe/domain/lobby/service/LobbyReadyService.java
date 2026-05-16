/*
 * 로비 준비 상태 변경 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 인증 주체 검증
 * - 로비 존재 여부 조회
 * - WAITING 상태 검증
 * - 로비 참여자 여부 검증
 * - 방장 ready 변경 차단
 * - Redis ready Set 갱신
 * - 로비 정보 refresh 이벤트 발행
 *
 * [주의]
 * 방장은 ready 대상이 아니다.
 * 방장은 ready 버튼 대신 게임 시작 버튼을 사용한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyReadyRequest;
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
public class LobbyReadyService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에는 입장할 수 없습니다.";
    private static final String ERROR_READY_FORBIDDEN =
            "로비 참여자만 준비 상태를 변경할 수 있습니다.";
    private static final String ERROR_HOST_READY_NOT_ALLOWED =
            "방장은 준비 상태를 변경하지 않고 시작 버튼을 사용합니다.";

    // =========================================================
    // 비즈니스 규칙 상수
    // =========================================================

    /**
     * ready 변경 가능한 로비 상태.
     *
     * Redis Hash에서 읽은 status 문자열과 비교하므로 name() 값으로 관리합니다.
     */
    private static final String LOBBY_STATUS_WAITING = LobbyStatus.WAITING.name();

    private final LobbyRepository lobbyRepository;
    private final LobbyEventService lobbyEventService;

    /**
     * 로비 참여자의 준비 상태를 변경한다.
     *
     * [정책]
     * - JWT 인증이 필요하다.
     * - Redis에 존재하는 로비만 대상으로 한다.
     * - WAITING 상태의 로비에서만 준비 상태를 변경할 수 있다.
     * - 로비 참여자만 준비 상태를 변경할 수 있다.
     * - 방장은 ready 대상에서 제외한다.
     * - 변경 완료 후 로비 내부 refresh 신호를 전송한다.
     *
     * [실시간 동기화]
     * ready 상태 변경 자체는 Redis ready Set에 반영한다.
     * 이후 클라이언트는 REFRESH_LOBBY_INFO 신호를 받고
     * GET /api/lobbies/{code}를 다시 호출해 최신 ready 상태를 동기화한다.
     *
     * @param code      로비 초대 코드
     * @param request   준비 상태 변경 요청
     * @param principal JWT에서 추출한 인증 주체
     */
    public void updateReadyStatus(
            String code,
            UpdateLobbyReadyRequest request,
            CustomPrincipal principal
    ) {
        if (principal == null || principal.userId() == null) {
            log.warn("로비 준비 상태 변경 거부 - principal 또는 userId가 null. code: {}", code);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        JoinLobbyResponse lobbyInfo = lobbyRepository.findByInviteCode(code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        if (!LOBBY_STATUS_WAITING.equals(lobbyInfo.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        String userIdentifier = principal.userIdentifier();

        boolean participant = lobbyRepository.isParticipant(code, userIdentifier);

        if (!participant) {
            log.warn(
                    "로비 준비 상태 변경 거부 - 참여자가 아님. code: {}, userIdentifier: {}, hostId: {}",
                    code,
                    userIdentifier,
                    lobbyInfo.hostId()
            );
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_READY_FORBIDDEN);
        }

        if (isLobbyHost(lobbyInfo, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_HOST_READY_NOT_ALLOWED);
        }

        lobbyRepository.updateReadyStatus(
                code,
                userIdentifier,
                Boolean.TRUE.equals(request.ready())
        );

        lobbyEventService.notifyLobbyInfoRefresh(code, userIdentifier);

        log.info(
                "로비 준비 상태 변경 완료 - code: {}, userIdentifier: {}, ready: {}",
                code,
                userIdentifier,
                request.ready()
        );
    }

    /**
     * 요청자가 로비 방장인지 확인한다.
     *
     * Redis 로비 정보의 hostId는 userIdentifier 기준으로 저장되므로,
     * JWT principal의 userIdentifier와 직접 비교합니다.
     *
     * @param lobbyInfo      Redis에서 조회한 로비 정보
     * @param userIdentifier 요청자 식별자
     * @return 방장이면 true
     */
    private boolean isLobbyHost(JoinLobbyResponse lobbyInfo, String userIdentifier) {
        return lobbyInfo.hostId() != null && lobbyInfo.hostId().equals(userIdentifier);
    }
}