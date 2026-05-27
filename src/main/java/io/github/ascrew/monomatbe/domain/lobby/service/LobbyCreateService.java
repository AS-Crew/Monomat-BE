/*
 * 로비 생성 유스케이스를 담당하는 서비스.
 *
 * [책임]
 * - 인증 주체 검증
 * - 방장 User 조회
 * - 선택된 맵 접근 가능 여부 검증 위임
 * - questionCount 자동 설정 (맵 선택 시 numOfSong 기준)
 * - Redis 로비 상태 생성
 * - DB GAME_LOBBY 스냅샷 저장
 * - DB 저장 실패 시 Redis 보상 삭제
 *
 * 로비 생성은 Redis 선저장 + DB 스냅샷 저장 + 보상 삭제라는 별도 트랜잭션 경계를 가지므로,
 * 독립 유스케이스 서비스로 분리한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
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
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyCreateService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_USER_NOT_FOUND =
            "사용자를 찾을 수 없습니다.";
    private static final String ERROR_CREATE_LOBBY_FAILED =
            "로비 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_INVALID_PRINCIPAL =
            "로비 생성 요청 거부 - principal 또는 userId가 null. userIdentifier: {}";
    private static final String LOG_DB_SAVE_FAILED =
            "DB 로비 저장 실패 - Redis 보상 삭제 시작. 코드: {}";
    private static final String LOG_COMPENSATION_SUCCESS =
            "Redis 보상 삭제 완료 - 코드: {}";
    private static final String LOG_COMPENSATION_FAILED =
            "Redis 보상 삭제 실패 - 코드: {}. 수동 정리 필요. [모니터링 필요]";
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final UserRepository userRepository;
    private final LobbyMapPolicy lobbyMapPolicy;
    private final QuizMapJpaRepository quizMapJpaRepository;

    /**
     * 로비를 생성한다.
     *
     * [처리 순서]
     * 1. principal null 및 userId null 검증
     * 2. JWT에서 추출한 userId로 User 엔티티 조회
     * 3. 선택된 mapId가 있으면 LobbyMapPolicy로 맵 존재/삭제/권한 검증
     * 4. questionCount 자동 설정:
     *    - mapId 있음: min(request.questionCount ?: numOfSong, numOfSong)
     *    - mapId 없음: request.questionCount ?: DEFAULT_QUESTION_COUNT
     * 5. Lua 스크립트로 초대 코드 선점 및 Redis 로비 데이터 원자 저장
     * 6. DB에 GAME_LOBBY 스냅샷 저장
     * 7. DB 저장 실패 시 Redis 보상 삭제
     *
     * [트랜잭션 경계]
     * 로비 생성은 Redis와 DB를 함께 다룬다.
     * DB 저장 실패 시 Redis 보상 삭제가 필요하므로 이 유스케이스 서비스에서 트랜잭션을 직접 관리한다.
     *
     * @param request   로비 생성 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 생성된 로비 정보 응답 DTO
     */
    @Transactional
    public CreateLobbyResponse createLobby(CreateLobbyRequest request, CustomPrincipal principal) {

        if (principal == null || principal.userId() == null) {
            log.warn(
                    LOG_INVALID_PRINCIPAL,
                    principal != null ? principal.userIdentifier() : "null"
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        User host = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_USER_NOT_FOUND
                ));

        /*
         * 맵 연결 정책:
         * - mapId가 없으면 맵 미선택 로비로 생성한다.
         * - mapId가 있으면 LobbyMapPolicy에서 존재 여부, 삭제 여부, 접근 권한을 검증한다.
         * - 검증된 최소 맵 정보만 Redis 저장 계층으로 전달한다.
         */
        LobbyMapMetadata mapMetadata = lobbyMapPolicy.resolveLobbyMapMetadata(
                request.mapId(),
                principal.userId()
        );

        int effectiveQuestionCount = resolveQuestionCount(request, mapMetadata);

        String inviteCode = lobbyRepository.saveToRedis(
                request,
                principal.userIdentifier(),
                mapMetadata,
                effectiveQuestionCount,
                request.timeLimitSeconds()
        );

        try {
            GameLobby gameLobby = gameLobbyJpaRepository.save(GameLobby.builder()
                    .host(host)
                    .mapId(mapMetadata != null ? mapMetadata.mapId() : null)
                    .inviteCode(inviteCode)
                    .title(request.title())
                    .maxPlayers(request.maxPlayers())
                    .questionCount(effectiveQuestionCount)
                    .timeLimitSeconds(request.timeLimitSeconds())
                    .isPrivate(request.isPrivate())
                    .build());

            log.info(
                    "로비 생성 완료 - 코드: {}, 방장: {}, mapId: {}, questionCount: {}",
                    inviteCode,
                    principal.userIdentifier(),
                    mapMetadata != null ? mapMetadata.mapId() : null,
                    effectiveQuestionCount
            );

            return CreateLobbyResponse.builder()
                    .lobbyId(gameLobby.getId())
                    .inviteCode(inviteCode)
                    .title(gameLobby.getTitle())
                    .maxPlayers(gameLobby.getMaxPlayers())
                    .isPrivate(gameLobby.getIsPrivate())
                    .status(gameLobby.getStatus().name())
                    .mapId(mapMetadata != null ? mapMetadata.mapId() : null)
                    .mapTitle(mapMetadata != null ? mapMetadata.mapTitle() : null)
                    .mapCategory(mapMetadata != null ? mapMetadata.mapCategory() : null)
                    .build();

        } catch (Exception e) {
            log.error(LOG_DB_SAVE_FAILED, inviteCode, e);

            boolean compensationSuccess = lobbyRepository.deleteFromRedis(inviteCode);

            if (compensationSuccess) {
                log.info(LOG_COMPENSATION_SUCCESS, inviteCode);
            } else {
                log.error(
                        "{} {} code: {}",
                        LOG_MONITORING_REQUIRED,
                        LOG_COMPENSATION_FAILED,
                        inviteCode
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_CREATE_LOBBY_FAILED
            );
        }
    }

    /**
     * 유효한 questionCount를 결정한다.
     *
     * [결정 규칙]
     * - mapId 없음: request.questionCount ?: DEFAULT_QUESTION_COUNT
     * - mapId 있음:
     *   - request.questionCount == null → numOfSong (맵 전체 문제 수로 자동 설정)
     *   - request.questionCount > numOfSong → 400 BAD_REQUEST
     *   - request.questionCount <= numOfSong → request.questionCount 그대로 사용
     *
     * LobbyMapPolicy가 이미 맵 존재·삭제·권한을 검증했으므로 findById는 항상 성공한다.
     */
    private int resolveQuestionCount(CreateLobbyRequest request, LobbyMapMetadata mapMetadata) {
        if (mapMetadata == null || mapMetadata.mapId() == null) {
            return (request.questionCount() == null)
                    ? LobbyDefaults.DEFAULT_QUESTION_COUNT
                    : request.questionCount();
        }

        QuizMap map = quizMapJpaRepository.findById(mapMetadata.mapId()).orElseThrow();
        int numOfSong = map.getNumOfSong();

        if (request.questionCount() == null) {
            return numOfSong;
        }

        if (request.questionCount() > numOfSong) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "설정한 문제 수(" + request.questionCount() + ")가 맵의 등록 곡 수(" + numOfSong + ")보다 많습니다."
            );
        }

        return request.questionCount();
    }
}
