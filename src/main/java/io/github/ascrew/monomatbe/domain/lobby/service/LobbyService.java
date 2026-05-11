/*
 * 로비 조회 관련 비즈니스 로직을 담당하는 서비스.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private static final String ERROR_USER_NOT_FOUND =
            "사용자를 찾을 수 없습니다.";
    private static final String ERROR_CREATE_LOBBY_FAILED =
            "로비 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에는 입장할 수 없습니다.";
    private static final String ERROR_LOBBY_FULL =
            "최대 인원에 도달한 로비입니다.";
    private static final String ERROR_MAP_NOT_FOUND =
            "존재하지 않는 맵입니다.";
    private static final String ERROR_MAP_DELETED =
            "삭제된 맵은 로비에 연결할 수 없습니다.";
    private static final String ERROR_PRIVATE_MAP_FORBIDDEN =
            "비공개 맵은 소유자만 로비에 연결할 수 있습니다.";

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
    private static final String LOG_JOIN_LOBBY_REQUEST =
            "로비 입장 요청 - 초대 코드: {}, 식별자: {}";
    private static final String LOG_JOIN_LOBBY_SUCCESS =
            "로비 입장 사전 검증 통과 - 초대 코드: {}, 식별자: {}, 현재 인원: {}/{}";

    // =========================================================
    // 비즈니스 규칙 상수
    // =========================================================

    /**
     * 입장 가능한 로비 상태
     * PLAYING, FINISHED 상태의 로비에는 입장할 수 없다.
     * LobbyStatus enum을 직접 비교하지 않고 Redis에서 읽은 문자열과 비교하므로
     * name()으로 변환하여 상수로 관리한다.
     */
    private static final String LOBBY_STATUS_WAITING = LobbyStatus.WAITING.name();

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final UserRepository userRepository;
    private final QuizMapJpaRepository quizMapJpaRepository;

    /**
     * 로비를 생성한다.
     *
     * [처리 순서]
     * 1. principal null 및 userId null 검증 → 401 반환
     * 2. JWT에서 추출한 userId로 User 엔티티 조회
     * 3. Lua 스크립트로 초대 코드 선점 및 Redis에 로비 데이터 원자적 저장
     * 4. DB에 GAME_LOBBY 스냅샷 Insert
     * 5. DB 실패 시 Redis 보상 삭제 및 성공/실패 여부를 구분하여 로그 기록
     *
     * @param request   로비 생성 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 생성된 로비 정보 응답 DTO
     */
    @Transactional
    public CreateLobbyResponse createLobby(CreateLobbyRequest request, CustomPrincipal principal) {

        // principal 및 userId null 방어
        // JwtAuthenticationFilter를 통과했더라도 토큰 파싱 이상으로
        // userId가 null인 채로 진입할 수 있으므로 서비스 레이어에서 재검증한다.
        if (principal == null || principal.userId() == null) {
            log.warn(LOG_INVALID_PRINCIPAL,
                    principal != null ? principal.userIdentifier() : "null");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        User host = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_USER_NOT_FOUND));

        /**
         * 맵 연결 정책 :
         * - mapId가 없으면 맵 미선택 로비로 생성한다.
         * - mapId가 있으면 존재 여부, 삭제 여부, 접근 권한을 검증한다.
         * - 검증된 최소 맵 정보만 Redis 저장 계층으로 전달한다.
         */
        LobbyMapMetadata mapMetadata = resolveLobbyMapMetadata(
                request.mapId(),
                principal.userId()
        );

        String inviteCode = lobbyRepository.saveToRedis(
                request,
                principal.userIdentifier(),
                mapMetadata
        );

        try {
            GameLobby gameLobby = gameLobbyJpaRepository.save(GameLobby.builder()
                    .host(host)
                    .mapId(mapMetadata != null ? mapMetadata.mapId() : null)
                    .inviteCode(inviteCode)
                    .title(request.title())
                    .maxPlayers(request.maxPlayers())
                    .roundCount(request.roundCount())
                    .timeLimitSeconds(request.timeLimitSeconds())
                    .isPrivate(request.isPrivate())
                    .build());

            log.info(
                    "로비 생성 완료 - 코드: {}, 방장: {}",
                    inviteCode,
                    principal.userIdentifier(),
                    mapMetadata != null ? mapMetadata.mapId() : null
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
                log.error(LOG_COMPENSATION_FAILED, inviteCode);
            }

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, ERROR_CREATE_LOBBY_FAILED);
        }
    }

    /**
     * 초대 코드 기반 로비 입장 사전 검증을 수행하고 로비 정보를 반환한다.
     *
     * [책임 범위]
     * 이 메서드는 입장 허가 검증만 담당한다.
     * 실제 Redis 참여자 등록은 클라이언트가 WebSocket을 연결하고,
     * /topic/lobby/{code}를 구독하는 시점에 enter_lobby.lua로 처리된다.
     *
     * [검증 순서]
     * Redis 조회 횟수를 최소화하기 위해 HGETALL (1회)로 로비 존재 여부와
     * 상태를 동시에 확인하고, 이후 SCARD (1회)로 인원을 확인한다.
     * 1. 로비 존재 여부 확인 -> 없으면 404
     * 2. 로비 상태 확인 -> WAITING이 아니면 409
     * 3. 인원 초과 확인 -> 초과면 409
     *
     * @param inviteCode 로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
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
                        HttpStatus.NOT_FOUND, ERROR_LOBBY_NOT_FOUND));

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

        log.info(LOG_JOIN_LOBBY_SUCCESS,
                inviteCode,
                principal.userIdentifier(),
                lobbyInfo.currentPlayers(),
                lobbyInfo.maxPlayers());

        return lobbyInfo;
    }

    /**
     * 공개 로비 목록을 조회한다.
     * Redis에서 직접 필터링하여 공개(isPrivate=false) 로비만 반환
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyRepository.getPublicLobbies();
    }

    /**
     * 로비 생성 시 선택된 맵의 접근 가능 여부를 검증하고,
     * Redis 저장에 필요한 최소 메타데이터로 변환합니다.
     *
     * [정책]
     * - mapId가 null이면 맵 미선택 로비로 생성한다.
     * - 삭제된 맵은 선택할 수 없다.
     * - 공개 맵은 누구나 로비에 연결할 수 있다.
     * - 비공개 맵은 소유자만 로비에 연결할 수 있다.
     *
     * @param mapId 요청으로 전달된 맵 ID
     * @param requesterUserId 로비 생성 요청자의 DB userId
     * @return 선택된 맵 메타데이터. 맵 미선택 시 null.
     */
    private LobbyMapMetadata resolveLobbyMapMetadata(Long mapId, Long requesterUserId) {
        if (mapId == null) {
            return null;
        }

        QuizMap quizMap = quizMapJpaRepository.findById(mapId)
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

        if (!canUseMap(quizMap, requesterUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_PRIVATE_MAP_FORBIDDEN
            );
        }

        return new LobbyMapMetadata(
                quizMap.getId(),
                quizMap.getTitle(),
                quizMap.getCategory().value()
        );
    }

    /**
     * 요청자가 해당 맵을 로비에 연결할 수 있는지 검증한다.
     *
     * 공개 맵은 누구나 사용할 수 있고, 비공개 맵은 맵 소유자만 사용할 수 있다.
     */
    private boolean canUseMap(QuizMap quizMap, Long requesterUserId) {
        if (Boolean.TRUE.equals(quizMap.getIsPublic())) {
            return true;
        }

        return quizMap.getOwner() != null
                && quizMap.getOwner().getId() != null
                && quizMap.getOwner().getId().equals(requesterUserId);
    }
}