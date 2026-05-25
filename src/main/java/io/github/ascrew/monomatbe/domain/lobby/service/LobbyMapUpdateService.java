/*
 * 로비 대기실 맵 변경 유스케이스를 담당하는 서비스.
 *
 * [책임]
 * - 인증 주체 검증 (userId / userIdentifier null 모두 차단)
 * - Redis 로비 1차 조회 (존재 여부 / WAITING 상태 / 방장 여부)
 * - 선택된 맵 접근 가능 여부 검증 위임 (LobbyMapPolicy 재사용)
 * - DB GAME_LOBBY 행에 대한 PESSIMISTIC_WRITE 락 획득 (게임 시작 경로와 직렬화)
 * - 락 획득 후 entity.status로 WAITING 재검증
 * - Redis 맵 메타데이터 갱신
 * - DB GAME_LOBBY.map_id 조건부 갱신
 * - 보상 복구를 status==WAITING 원자 조건으로 처리 (compensate_lobby_map.lua)
 * - DB row 누락 시 LobbyStartService와 동일한 reconciliation 패턴 적용
 * - 참여자 refresh 이벤트 발행 (트랜잭션 커밋 후)
 *
 * [정합성 정책]
 * Redis 1차 조회 → DB 행 락 → Redis 선갱신 → DB 조건부 갱신 순으로 진행한다.
 * DB 락이 게임 시작 경로(LobbyStartService.findByInviteCodeForUpdate)와 직렬화를 보장한다.
 * 보상 복구는 항상 Lua로 status==WAITING 원자 검증과 함께 수행되어
 * 이미 PLAYING으로 전환된 로비의 맵 메타데이터를 절대 건드리지 않는다.
 *
 * [Redis 선갱신 정책]
 * Redis가 로비 실시간 상태의 source of truth이므로 맵 변경도 Redis를 먼저 반영하여
 * 클라이언트 가시성(STOMP refresh로 즉시 노출)을 우선한다. DB는 영속/감사 스냅샷 역할이며,
 * DB 갱신 실패 시 compensate_lobby_map.lua가 status==WAITING 원자 검증 후 보상한다.
 * 자세한 정책은 docs/ARCHITECTURE.md "로비 맵 변경 Redis-DB 정합성" 섹션 참고.
 *
 * [보상 실패 시 운영 대응]
 * compensate Lua가 LOBBY_NOT_FOUND를 반환하거나 예외가 발생하면 [MONITORING_REQUIRED] 로그가
 * 남는다. 운영자는 해당 로그로 Redis/DB mapId 불일치를 수동 확인하고 정리한다.
 *
 * [Lock timeout 정책]
 * findByInviteCodeForUpdate에 jakarta.persistence.lock.timeout = 3000ms 힌트가 적용되어
 * 있다. 타임아웃 초과 시 PessimisticLockingFailureException을 잡아 409 CONFLICT로 변환한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LobbyMapCompensationResult;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

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
    private static final String ERROR_UPDATE_MAP_FAILED =
            "맵 변경에 실패했습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_LOBBY_SNAPSHOT_NOT_FOUND =
            "로비 상태 정보가 일치하지 않습니다. 로비를 다시 생성해주세요.";
    private static final String ERROR_LOBBY_LOCK_CONTENTION =
            "다른 로비 상태 변경이 진행 중입니다. 잠시 후 다시 시도해주세요.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";
    private static final String LOG_DB_UPDATE_FAILED =
            "DB 맵 갱신 실패 - Redis 보상 복구 시작. code: {}";
    private static final String LOG_COMPENSATION_SKIPPED =
            "Redis 맵 보상 복구 스킵 (status가 더 이상 WAITING이 아님) - code: {}";

    private static final String RECONCILIATION_REASON_MAP_UPDATE_SNAPSHOT_NOT_FOUND =
            "MAP_UPDATE_DB_SNAPSHOT_NOT_FOUND";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final LobbyMapPolicy lobbyMapPolicy;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;

    /**
     * 로비 대기실에서 방장의 맵 변경 요청을 처리한다.
     *
     * [처리 순서]
     * 1. principal / userId / userIdentifier null 검증
     * 2. Redis 1차 조회 (존재 / WAITING / 방장 검증)
     * 3. LobbyMapPolicy로 맵 존재/삭제/권한 검증 (newMetadata 결정)
     * 4. DB GAME_LOBBY 행 PESSIMISTIC_WRITE 락 획득
     *    - row 없음 → handleMissingGameLobbySnapshot (409 SNAPSHOT_NOT_FOUND + reconciliation)
     *    - status != WAITING → 409 NOT_WAITING (락 시점의 진실)
     * 5. Redis 맵 메타데이터 선갱신
     * 6. DB GAME_LOBBY.map_id 조건부 갱신 (방어용 - 락 보장으로 사실상 항상 1)
     *    - 갱신 행 0 → 안전 보상 (compensateMapMetadataIfWaiting) 후 409
     *    - DB 예외 → 안전 보상 후 500
     * 7. 트랜잭션 커밋 후 참여자 refresh 이벤트 발행
     *
     * @param code      로비 초대 코드
     * @param request   맵 변경 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     */
    @Transactional
    public void updateMap(String code, UpdateLobbyMapRequest request, CustomPrincipal principal) {

        if (principal == null
                || principal.userId() == null
                || principal.userIdentifier() == null) {
            log.warn(
                    "로비 맵 변경 요청 거부 - principal/userId/userIdentifier null. code: {}",
                    code
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        JoinLobbyResponse lobbyInfo = lobbyRepository.findByInviteCode(code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        if (!LobbyStatus.WAITING.name().equals(lobbyInfo.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        if (!Objects.equals(principal.userIdentifier(), lobbyInfo.hostId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_NOT_HOST);
        }

        LobbyMapMetadata newMetadata = lobbyMapPolicy.resolveLobbyMapMetadata(
                request.mapId(),
                principal.userId()
        );

        LobbyMapMetadata oldMetadata = lobbyInfo.mapId() == null
                ? null
                : new LobbyMapMetadata(lobbyInfo.mapId(), lobbyInfo.mapTitle(), lobbyInfo.mapCategory());

        GameLobby gameLobby = acquireGameLobbyRowLock(code, principal.userIdentifier());

        if (gameLobby.getStatus() != LobbyStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        lobbyRepository.updateMapMetadata(code, newMetadata);

        Long newMapId = newMetadata != null ? newMetadata.mapId() : null;
        int updated;
        try {
            updated = gameLobbyJpaRepository.updateMapIdIfWaiting(code, newMapId, LobbyStatus.WAITING);
        } catch (Exception e) {
            log.error(LOG_DB_UPDATE_FAILED, code, e);
            compensateRedisMapMetadata(code, oldMetadata);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_UPDATE_MAP_FAILED);
        }

        if (updated == 0) {
            log.error(
                    "{} 락 보유 중에 updateMapIdIfWaiting이 0행 반환 - 정합성 이상. code: {}",
                    LOG_MONITORING_REQUIRED,
                    code
            );
            compensateRedisMapMetadata(code, oldMetadata);
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        log.info(
                "로비 맵 변경 완료 - code: {}, host: {}, oldMapId: {}, newMapId: {}",
                code,
                principal.userIdentifier(),
                oldMetadata != null ? oldMetadata.mapId() : null,
                newMapId
        );

        registerMapChangedEventAfterCommit(code);
    }

    /**
     * GAME_LOBBY 행에 대한 PESSIMISTIC_WRITE 락을 획득한다.
     *
     * [예외 처리]
     * - row 부재 → handleMissingGameLobbySnapshot 분기 (409 SNAPSHOT_NOT_FOUND)
     * - 락 획득 타임아웃(3초) → PessimisticLockingFailureException을 잡아 409 CONFLICT로 변환.
     *   클라이언트가 "다른 상태 변경 진행 중" 의미를 명확히 받을 수 있게 한다.
     */
    private GameLobby acquireGameLobbyRowLock(String code, String requesterIdentifier) {
        try {
            return gameLobbyJpaRepository.findByInviteCodeForUpdate(code)
                    .orElseGet(() -> handleMissingGameLobbySnapshot(code, requesterIdentifier));
        } catch (PessimisticLockingFailureException e) {
            log.warn(
                    "로비 맵 변경 락 획득 타임아웃 - code: {}, requester: {}",
                    code,
                    requesterIdentifier,
                    e
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_LOCK_CONTENTION);
        }
    }

    /**
     * Redis 로비는 존재하지만 DB GAME_LOBBY 스냅샷이 없는 정합성 이상 상태를 처리한다.
     *
     * LobbyStartService.handleMissingGameLobbySnapshot과 동일한 패턴:
     * Redis 잔존 로비 보상 삭제를 시도하고, 실패하면 reconciliation 큐에 적재한다.
     */
    private GameLobby handleMissingGameLobbySnapshot(String code, String requesterIdentifier) {
        log.error(
                "{} Redis 로비는 존재하지만 DB GAME_LOBBY 스냅샷이 없습니다. "
                        + "맵 변경 경로에서 감지 - Redis 잔존 로비 보상 삭제를 시도합니다. code: {}, requester: {}",
                LOG_ALERT_REQUIRED,
                code,
                requesterIdentifier
        );

        boolean deleted = lobbyRepository.deleteFromRedis(code);

        if (!deleted) {
            lobbyRepository.enqueueStartReconciliation(
                    code,
                    RECONCILIATION_REASON_MAP_UPDATE_SNAPSHOT_NOT_FOUND
            );

            log.error(
                    "{} DB 스냅샷 누락 로비 Redis 삭제 실패 - 재처리 큐 적재 완료. code: {}, requester: {}",
                    LOG_ALERT_REQUIRED,
                    code,
                    requesterIdentifier
            );
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                ERROR_LOBBY_SNAPSHOT_NOT_FOUND
        );
    }

    /**
     * Redis 맵 메타데이터를 status==WAITING 원자 조건으로 보상 복구한다.
     *
     * [안전 정책]
     * 보상 시점에 다른 트랜잭션이 status를 PLAYING으로 바꿨다면 oldMetadata로 되돌리면 안 된다.
     * Lua 내부에서 원자 검증하므로 SKIPPED_NOT_WAITING은 정상 경로로 간주하고 로그만 남긴다.
     */
    private void compensateRedisMapMetadata(String code, LobbyMapMetadata oldMetadata) {
        LobbyMapCompensationResult result = lobbyRepository.compensateMapMetadataIfWaiting(code, oldMetadata);

        switch (result) {
            case COMPENSATED -> log.info("Redis 맵 보상 복구 완료 - code: {}", code);
            case SKIPPED_NOT_WAITING -> log.warn(LOG_COMPENSATION_SKIPPED, code);
            case LOBBY_NOT_FOUND -> log.error(
                    "{} Redis 맵 보상 복구 실패 - 로비 데이터 없음 또는 Lua 실행 실패. code: {}",
                    LOG_MONITORING_REQUIRED,
                    code
            );
        }
    }

    private void registerMapChangedEventAfterCommit(String code) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error(
                    "{} 맵 변경 이벤트 afterCommit 등록 실패 - 트랜잭션 동기화 비활성. code: {}",
                    LOG_MONITORING_REQUIRED,
                    code
            );
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_UPDATE_MAP_FAILED);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lobbyRealtimeNotifier.notifyLobbyInfoRefresh(code);
            }
        });
    }
}
