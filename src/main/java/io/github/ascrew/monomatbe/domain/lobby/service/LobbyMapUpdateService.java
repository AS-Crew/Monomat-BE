/*
 * 로비 대기실 맵 변경 유스케이스를 담당하는 서비스.
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
     * 새 맵이 선택되면 questionCount는 새 맵의 numOfSong으로 재설정한다.
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
                : new LobbyMapMetadata(
                lobbyInfo.mapId(),
                lobbyInfo.mapTitle(),
                lobbyInfo.mapCategory(),
                null
        );

        GameLobby gameLobby = acquireGameLobbyRowLock(code, principal.userIdentifier());

        if (gameLobby.getStatus() != LobbyStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        int oldQuestionCount = gameLobby.getQuestionCount();
        Long newMapId = newMetadata != null ? newMetadata.mapId() : null;
        int newQuestionCount = resolveNewQuestionCount(gameLobby, newMetadata);

        lobbyRepository.updateMapMetadata(code, newMetadata, newQuestionCount);

        try {
            gameLobby.updateMapAndQuestionCount(newMapId, newQuestionCount);
            gameLobbyJpaRepository.saveAndFlush(gameLobby);
        } catch (Exception e) {
            log.error(LOG_DB_UPDATE_FAILED, code, e);
            compensateRedisMapMetadata(code, oldMetadata, oldQuestionCount);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_UPDATE_MAP_FAILED);
        }

        log.info(
                "로비 맵 변경 완료 - code: {}, host: {}, oldMapId: {}, newMapId: {}, newQuestionCount: {}",
                code,
                principal.userIdentifier(),
                oldMetadata != null ? oldMetadata.mapId() : null,
                newMapId,
                newQuestionCount
        );

        registerMapChangedEventAfterCommit(code);
    }

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

    private void compensateRedisMapMetadata(String code, LobbyMapMetadata oldMetadata, int oldQuestionCount) {
        try {
            LobbyMapCompensationResult result =
                    lobbyRepository.compensateMapMetadataIfWaiting(code, oldMetadata, oldQuestionCount);

            switch (result) {
                case COMPENSATED -> log.info("Redis 맵 보상 복구 완료 - code: {}", code);
                case SKIPPED_NOT_WAITING -> log.warn(LOG_COMPENSATION_SKIPPED, code);
                case LOBBY_NOT_FOUND -> log.error(
                        "{} Redis 맵 보상 복구 불가 - Redis에 로비 없음 (이미 삭제됨). code: {}",
                        LOG_MONITORING_REQUIRED,
                        code
                );
            }
        } catch (Exception e) {
            log.error(
                    "{} Redis 맵 보상 복구 실패 - Redis 인프라 오류 (연결 단절/타임아웃/Lua 로딩 등). "
                            + "Redis-DB mapId 불일치 가능성 있음. code: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    e
            );
        }
    }

    private int resolveNewQuestionCount(GameLobby gameLobby, LobbyMapMetadata newMetadata) {
        if (newMetadata != null && newMetadata.mapId() != null) {
            return newMetadata.numOfSong();
        }

        return gameLobby.getQuestionCount();
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