/*
 * 로비 관련 유스케이스 facade 서비스.
 *
 * [현재 책임]
 * - 기존 LobbyController의 의존성을 유지하기 위한 facade 역할
 * - 각 로비 유스케이스 서비스로 점진적으로 책임을 위임
 *
 * [Issue #78 리팩토링 방향]
 * 기존 LobbyService는 로비 생성, 입장, 조회, ready, 시작, 맵 검증,
 * Redis/DB 보상 처리까지 모두 담당하고 있었습니다.
 *
 * 이번 이슈에서는 기능 동작을 바꾸지 않고,
 * 유스케이스 단위 서비스로 책임을 점진적으로 분리합니다.
 *
 * [1단계]
 * - createLobby() 로직을 LobbyCreateService로 분리
 *
 * [2단계]
 * - joinLobby() 로직을 LobbyJoinService로 분리
 *
 * [3단계]
 * - getPublicLobbies(), getLobbyDetail() 로직을 LobbyQueryService로 분리
 *
 * [4단계]
 * - updateReadyStatus() 로직을 LobbyReadyService로 분리
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyReadyRequest;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에는 입장할 수 없습니다.";
    private static final String ERROR_START_FORBIDDEN =
            "방장만 게임을 시작할 수 있습니다.";
    private static final String ERROR_START_HOST_NOT_FOUND =
            "로비 방장 정보가 유효하지 않습니다.";
    private static final String ERROR_START_MAP_NOT_SELECTED =
            "게임을 시작하려면 맵을 선택해야 합니다.";
    private static final String ERROR_START_NO_PLAYER =
            "게임을 시작하려면 방장을 제외한 참여자가 1명 이상 필요합니다.";
    private static final String ERROR_START_NOT_READY =
            "모든 참여자가 준비 완료 상태여야 합니다.";
    private static final String ERROR_START_MAP_SONG_COUNT_NOT_ENOUGH =
            "맵의 문제 수가 설정된 라운드 수보다 적습니다.";
    private static final String ERROR_START_FAILED =
            "게임 시작 처리에 실패했습니다.";
    private static final String ERROR_START_DB_SYNC_FAILED =
            "게임 시작 상태 동기화에 실패했습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_MAP_NOT_FOUND =
            "존재하지 않는 맵입니다.";
    private static final String ERROR_MAP_DELETED =
            "삭제된 맵은 로비에 연결할 수 없습니다.";
    private static final String RECONCILIATION_REASON_DB_SYNC_FAILED =
            "START_DB_SYNC_FAILED";
    private static final String ERROR_START_EVENT_TRANSACTION_REQUIRED =
            "게임 시작 이벤트 발행을 위한 트랜잭션 동기화가 활성화되어 있지 않습니다.";
    private static final String ERROR_LOBBY_SNAPSHOT_NOT_FOUND =
            "로비 상태 정보가 일치하지 않습니다. 로비를 다시 생성해주세요.";
    private static final String RECONCILIATION_REASON_DB_SNAPSHOT_NOT_FOUND =
            "START_DB_SNAPSHOT_NOT_FOUND";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";

    private final LobbyCreateService lobbyCreateService;
    private final LobbyJoinService lobbyJoinService;
    private final LobbyQueryService lobbyQueryService;
    private final LobbyReadyService lobbyReadyService;
    private final LobbyRepository lobbyRepository;
    private final LobbyEventService lobbyEventService;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final QuizMapJpaRepository quizMapJpaRepository;

    /**
     * 로비 생성 facade 메서드.
     *
     * [Issue #78 - 1단계]
     * 실제 로비 생성 책임은 LobbyCreateService로 이동했습니다.
     * 기존 LobbyController의 의존성을 유지하기 위해 동일한 public method 계약만 남깁니다.
     *
     * @param request   로비 생성 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 생성된 로비 정보 응답 DTO
     */
    public CreateLobbyResponse createLobby(CreateLobbyRequest request, CustomPrincipal principal) {
        return lobbyCreateService.createLobby(request, principal);
    }

    /**
     * 초대 코드 기반 로비 입장 사전 검증 facade 메서드.
     *
     * [Issue #78 - 2단계]
     * 실제 입장 사전 검증 책임은 LobbyJoinService로 이동했습니다.
     * 기존 LobbyController의 의존성을 유지하기 위해 동일한 public method 계약만 남깁니다.
     *
     * @param inviteCode 로비 초대 코드
     * @param principal  JWT에서 추출한 인증 주체
     * @return 로비 응답 DTO
     */
    public JoinLobbyResponse joinLobby(String inviteCode, CustomPrincipal principal) {
        return lobbyJoinService.joinLobby(inviteCode, principal);
    }

    /**
     * 공개 로비 목록 조회 facade 메서드.
     *
     * [Issue #78 - 3단계]
     * 실제 공개 로비 목록 조회 책임은 LobbyQueryService로 이동했습니다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyQueryService.getPublicLobbies();
    }

    /**
     * 로비 상세 조회 facade 메서드.
     *
     * [Issue #78 - 3단계]
     * 실제 로비 상세 조회 책임은 LobbyQueryService로 이동했습니다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     * @return 로비 상세 응답
     */
    public LobbyDetailResponse getLobbyDetail(String code, CustomPrincipal principal) {
        return lobbyQueryService.getLobbyDetail(code, principal);
    }

    /**
     * 로비 ready 상태 변경 facade 메서드.
     *
     * [Issue #78 - 4단계]
     * 실제 ready 상태 변경 책임은 LobbyReadyService로 이동했습니다.
     *
     * @param code      로비 초대 코드
     * @param request   ready 변경 요청
     * @param principal JWT에서 추출한 인증 주체
     */
    public void updateReadyStatus(
            String code,
            UpdateLobbyReadyRequest request,
            CustomPrincipal principal
    ) {
        lobbyReadyService.updateReadyStatus(code, request, principal);
    }

    /**
     * 로비 게임 시작 요청을 처리한다.
     *
     * [검증 순서]
     * 1. 인증 정보 확인
     * 2. Redis 로비 존재 여부 확인
     * 3. DB 로비 스냅샷 조회
     * 4. 선택된 맵 존재 및 삭제 여부 확인
     * 5. 맵 문제 수가 roundCount 이상인지 확인
     * 6. start_lobby.lua로 방장 권한, WAITING 상태, ready 상태를 원자 검증
     * 7. DB 로비 상태를 PLAYING으로 변경
     * 8. 로비 참여자에게 게임 시작 이벤트 브로드캐스트
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     */
    @Transactional
    public void startLobbyGame(String code, CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            log.warn("게임 시작 요청 거부 - principal 또는 userId가 null. code: {}", code);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        String requesterIdentifier = principal.userIdentifier();

        if (lobbyRepository.findByInviteCode(code).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );
        }

        GameLobby gameLobby = gameLobbyJpaRepository.findByInviteCode(code)
                .orElseGet(() -> handleMissingGameLobbySnapshot(code, requesterIdentifier));

        QuizMap quizMap = validateStartMap(gameLobby);

        validateMapSongCount(quizMap, gameLobby);

        /*
         * Redis Lua가 status=PLAYING으로 변경되기 전에 트랜잭션 동기화 활성 여부를 확인합니다.
         *
         * 이 검증이 없으면 Redis는 PLAYING으로 바뀐 뒤,
         * afterCommit 이벤트 등록 단계에서 500이 발생할 수 있습니다.
         * 그 경우 클라이언트는 GAME_STARTED를 받지 못하고,
         * Redis/DB 상태 불일치도 남을 수 있습니다.
         */
        validateTransactionSynchronizationForGameStart(code, requesterIdentifier);

        StartLobbyResult result = lobbyRepository.executeStartLobbyProcess(
                code,
                requesterIdentifier
        );

        handleStartLobbyResult(result);

        try {
            gameLobby.changeStatus(LobbyStatus.PLAYING);
            gameLobbyJpaRepository.saveAndFlush(gameLobby);
        } catch (Exception e) {
            log.error(
                    "게임 시작 DB 상태 변경 실패 - Redis 상태 보상 롤백 시도. code: {}, requester: {}",
                    code,
                    requesterIdentifier,
                    e
            );

            boolean rollbackSucceeded = lobbyRepository.rollbackStartedLobbyStatus(code);

            if (!rollbackSucceeded) {
                lobbyRepository.enqueueStartReconciliation(
                        code,
                        RECONCILIATION_REASON_DB_SYNC_FAILED
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_START_DB_SYNC_FAILED
            );
        }

        registerGameStartedEventAfterCommit(code, requesterIdentifier);

        log.info(
                "게임 시작 처리 완료 - code: {}, requester: {}, mapId: {}, roundCount: {}",
                code,
                requesterIdentifier,
                quizMap.getId(),
                gameLobby.getRoundCount()
        );
    }

    /**
     * Redis에는 로비가 존재하지만 DB GAME_LOBBY 스냅샷이 없는 비정상 상태를 처리한다.
     *
     * [발생 가능한 상황]
     * - 로비 생성 중 Redis 저장은 성공했지만 DB 저장 실패 후 Redis 보상 삭제가 실패한 경우
     * - 운영 중 Redis/DB 정합성이 깨진 경우
     *
     * [처리 정책]
     * 이 상태에서는 roundCount, timeLimitSeconds, DB 상태 동기화 대상이 없으므로
     * 게임 시작을 진행할 수 없습니다.
     *
     * 따라서 Redis 잔존 로비를 보상 삭제하고,
     * 삭제 실패 시 재처리 큐에 적재합니다.
     */
    private GameLobby handleMissingGameLobbySnapshot(
            String code,
            String requesterIdentifier
    ) {
        log.error(
                "{} Redis 로비는 존재하지만 DB GAME_LOBBY 스냅샷이 없습니다. "
                        + "Redis 잔존 로비 보상 삭제를 시도합니다. code: {}, requester: {}",
                LOG_ALERT_REQUIRED,
                code,
                requesterIdentifier
        );

        boolean deleted = lobbyRepository.deleteFromRedis(code);

        if (!deleted) {
            lobbyRepository.enqueueStartReconciliation(
                    code,
                    RECONCILIATION_REASON_DB_SNAPSHOT_NOT_FOUND
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
     * 게임 시작 처리 전 트랜잭션 동기화 활성 여부를 검증한다.
     *
     * [이유]
     * GAME_STARTED 이벤트는 DB GAME_LOBBY 상태가 PLAYING으로 커밋된 이후에만 발행되어야 합니다.
     * 따라서 afterCommit 등록이 가능한 트랜잭션 동기화 상태가 아니면
     * 게임 시작 처리를 진행하면 안 됩니다.
     *
     * [중요]
     * 이 검증은 Redis start_lobby.lua 실행 전에 수행해야 합니다.
     */
    private void validateTransactionSynchronizationForGameStart(
            String code,
            String requesterIdentifier
    ) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        log.error(
                "{} 게임 시작 요청 거부 - 트랜잭션 동기화 비활성. "
                        + "Redis 상태 변경 전에 차단합니다. code: {}, requester: {}",
                LOG_ALERT_REQUIRED,
                code,
                requesterIdentifier
        );

        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ERROR_START_EVENT_TRANSACTION_REQUIRED
        );
    }

    /**
     * 게임 시작 이벤트를 DB 트랜잭션 커밋 이후에 발행한다.
     */
    private void registerGameStartedEventAfterCommit(
            String code,
            String requesterIdentifier
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error(
                    "{} 게임 시작 이벤트 afterCommit 등록 실패 - 트랜잭션 동기화 비활성. "
                            + "code: {}, requester: {}",
                    LOG_ALERT_REQUIRED,
                    code,
                    requesterIdentifier
            );
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_START_EVENT_TRANSACTION_REQUIRED
            );
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lobbyEventService.notifyGameStarted(code);
                lobbyEventService.notifyLobbyInfoRefresh(code, requesterIdentifier);
            }
        });
    }

    /**
     * 게임 시작에 사용할 맵을 검증한다.
     *
     * [검증]
     * - 로비에 mapId가 있어야 한다.
     * - 맵이 존재해야 한다.
     * - 삭제된 맵이면 시작할 수 없다.
     */
    private QuizMap validateStartMap(GameLobby gameLobby) {
        if (gameLobby.getMapId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_MAP_NOT_SELECTED
            );
        }

        QuizMap quizMap = quizMapJpaRepository.findById(gameLobby.getMapId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_MAP_NOT_FOUND
                ));

        if (Boolean.TRUE.equals(quizMap.getIsDeleted())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_MAP_DELETED
            );
        }

        return quizMap;
    }

    /**
     * 맵의 문제 수가 로비 라운드 수 이상인지 검증한다.
     *
     * Data API 없이 게임을 운영하므로,
     * 실제 출제 가능 여부는 맵에 저장된 문제 수(numOfSong)를 기준으로 판단합니다.
     */
    private void validateMapSongCount(QuizMap quizMap, GameLobby gameLobby) {
        Integer numOfSong = quizMap.getNumOfSong();
        Integer roundCount = gameLobby.getRoundCount();

        if (numOfSong == null || roundCount == null || numOfSong < roundCount) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_MAP_SONG_COUNT_NOT_ENOUGH
            );
        }
    }

    /**
     * 게임 시작 Lua 결과를 HTTP 예외 또는 성공 흐름으로 변환한다.
     */
    private void handleStartLobbyResult(StartLobbyResult result) {
        switch (result) {
            case StartLobbyResult.Started ignored -> {
                return;
            }

            case StartLobbyResult.LobbyNotFound ignored -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );

            case StartLobbyResult.HostNotFound ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_HOST_NOT_FOUND
            );

            case StartLobbyResult.Forbidden ignored -> throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_START_FORBIDDEN
            );

            case StartLobbyResult.LobbyNotWaiting ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_LOBBY_NOT_WAITING
            );

            case StartLobbyResult.MapNotSelected ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_MAP_NOT_SELECTED
            );

            case StartLobbyResult.NoPlayer ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_NO_PLAYER
            );

            case StartLobbyResult.NotReady notReady -> {
                log.warn(
                        "게임 시작 요청 거부 - 준비하지 않은 참여자 존재. code: {}, userIdentifier: {}",
                        notReady.lobbyCode(),
                        notReady.userIdentifier()
                );
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ERROR_START_NOT_READY
                );
            }

            case StartLobbyResult.Error error -> {
                log.error("게임 시작 처리 실패 - reason: {}", error.reason());
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ERROR_START_FAILED
                );
            }

            case StartLobbyResult.StaleParticipant staleParticipant -> {
                log.warn(
                        "게임 시작 요청 거부 - stale 참여자 감지. code: {}, userIdentifier: {}",
                        staleParticipant.lobbyCode(),
                        staleParticipant.userIdentifier()
                );
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ERROR_START_NOT_READY
                );
            }
        }
    }
}