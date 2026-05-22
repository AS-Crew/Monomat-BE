/*
 * 로비 대기실 맵 변경 유스케이스를 담당하는 서비스.
 *
 * [책임]
 * - 인증 주체 검증
 * - 로비 존재 여부 조회
 * - WAITING 상태 검증
 * - 방장 여부 검증
 * - 선택된 맵 접근 가능 여부 검증 위임 (LobbyMapPolicy 재사용)
 * - Redis 맵 메타데이터 갱신
 * - DB GAME_LOBBY.map_id 갱신
 * - DB 갱신 실패 시 Redis 보상 복구
 * - 참여자 refresh 이벤트 발행
 *
 * [정합성 정책]
 * Redis 선갱신 후 DB를 갱신한다. DB 갱신 실패 시 Redis를 이전 값으로 보상 복구한다.
 * 보상 복구도 실패하면 [MONITORING_REQUIRED] 로그를 남기고 운영자 수동 처리에 위임한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyMapRequest;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyMapUpdateService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에서는 맵을 변경할 수 없습니다.";
    private static final String ERROR_NOT_HOST =
            "방장만 맵을 변경할 수 있습니다.";
    private static final String ERROR_LOBBY_DB_NOT_FOUND =
            "DB에서 로비를 찾을 수 없습니다.";
    private static final String ERROR_UPDATE_MAP_FAILED =
            "맵 변경에 실패했습니다. 잠시 후 다시 시도해주세요.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";
    private static final String LOG_DB_UPDATE_FAILED =
            "DB 맵 갱신 실패 - Redis 보상 복구 시작. code: {}";
    private static final String LOG_COMPENSATION_SUCCESS =
            "Redis 맵 보상 복구 완료 - code: {}";
    private static final String LOG_COMPENSATION_FAILED =
            "Redis 맵 보상 복구 실패 - code: {}. Redis-DB 불일치 상태. 수동 정리 필요. [모니터링 필요]";

    private static final String LOBBY_STATUS_WAITING = LobbyStatus.WAITING.name();

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final LobbyMapPolicy lobbyMapPolicy;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;

    /**
     * 로비 대기실에서 방장의 맵 변경 요청을 처리한다.
     *
     * [처리 순서]
     * 1. principal null 및 userId null 검증
     * 2. Redis에서 로비 조회 (존재 여부 및 상태 확인)
     * 3. WAITING 상태 검증
     * 4. 방장 여부 검증
     * 5. LobbyMapPolicy로 맵 존재/삭제/권한 검증
     * 6. Redis 맵 메타데이터 갱신 (선반영)
     * 7. DB GAME_LOBBY.map_id 갱신 (실패 시 Redis 보상 복구)
     * 8. 참여자 refresh 이벤트 발행
     *
     * @param code      로비 초대 코드
     * @param request   맵 변경 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     */
    @Transactional
    public void updateMap(String code, UpdateLobbyMapRequest request, CustomPrincipal principal) {

        if (principal == null || principal.userId() == null) {
            log.warn(
                    "로비 맵 변경 요청 거부 - principal 또는 userId가 null. code: {}",
                    code
            );
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

        if (!principal.userIdentifier().equals(lobbyInfo.hostId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_NOT_HOST);
        }

        LobbyMapMetadata newMetadata = lobbyMapPolicy.resolveLobbyMapMetadata(
                request.mapId(),
                principal.userId()
        );

        LobbyMapMetadata oldMetadata = new LobbyMapMetadata(
                lobbyInfo.mapId(),
                lobbyInfo.mapTitle(),
                lobbyInfo.mapCategory()
        );

        lobbyRepository.updateMapMetadata(code, newMetadata);

        try {
            GameLobby gameLobby = gameLobbyJpaRepository.findByInviteCode(code)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            ERROR_LOBBY_DB_NOT_FOUND
                    ));

            gameLobby.updateMap(newMetadata != null ? newMetadata.mapId() : null);
            gameLobbyJpaRepository.saveAndFlush(gameLobby);

            log.info(
                    "로비 맵 변경 완료 - code: {}, host: {}, oldMapId: {}, newMapId: {}",
                    code,
                    principal.userIdentifier(),
                    oldMetadata.mapId(),
                    newMetadata != null ? newMetadata.mapId() : null
            );

        } catch (Exception e) {
            log.error(LOG_DB_UPDATE_FAILED, code, e);

            try {
                lobbyRepository.updateMapMetadata(code, oldMetadata);
                log.info(LOG_COMPENSATION_SUCCESS, code);
            } catch (Exception compensationException) {
                log.error(
                        "{} {} code: {}",
                        LOG_MONITORING_REQUIRED,
                        LOG_COMPENSATION_FAILED,
                        code,
                        compensationException
                );
            }

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_UPDATE_MAP_FAILED);
        }

        lobbyRealtimeNotifier.notifyLobbyInfoRefresh(code);
    }
}
