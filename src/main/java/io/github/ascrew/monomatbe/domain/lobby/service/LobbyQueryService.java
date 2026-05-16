/*
 * 로비 조회 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 공개 로비 목록 조회
 * - 로비 대기실 상세 조회
 * - 로비 상세 조회 접근 권한 검증
 * - 참여자 목록 조회 및 방장 누락 보정
 * - ready 상태 조회 및 플레이어 응답 조립
 * - 조회 시점의 canStart 계산
 *
 * [주의]
 * canStart는 실제 게임 시작 가능 여부를 확정하는 값이 아니다.
 * FE의 시작 버튼 활성화를 위한 조회 시점 snapshot 값으로 활용한다.
 * 실제 시작 가능 여부는 POST /api/lobbies/{code}/start에서 Redis Lua로 최종 검증한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPlayerResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyQueryService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_DETAIL_FORBIDDEN =
            "로비 참여자만 로비 상세 정보를 조회할 수 있습니다.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";

    // =========================================================
    // 비즈니스 규칙 상수
    // =========================================================

    /**
     * 로비 시작 가능 여부 계산 기준 상태
     *
     * Redis Hash에서 읽은 status 문자열과 비교하므로 name() 값으로 관리한다.
     */
    private static final String LOBBY_STATUS_WAITING = LobbyStatus.WAITING.name();

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final QuizMapJpaRepository quizMapJpaRepository;

    /**
     * 공개 로비 목록을 조회한다.
     *
     * [조회 기준]
     * Redis의 공개 로비 Set을 기준으로 현재 활성화된 공개 로비만 반환한다.
     *
     * [추후 확장]
     * 로비 목록 검색, 카테고리 필터, 정렬 기능은 이 조회 유스케이스를 기준으로 확장할 수 있다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    @Transactional(readOnly = true)
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyRepository.getPublicLobbies();
    }

    /**
     * 로비 대기실 상세 정보를 조회한다.
     *
     * [조회 대상]
     * - 로비 기본 정보
     * - DB에 저장된 룰 정보(roundCount, timeLimitSeconds)
     * - Redis 참여자 목록
     * - Redis ready 상태
     * - 현재 시작 가능 여부(canStart)
     *
     * [접근 정책]
     * 로비 상세 정보에는 참여자 ready 상태가 포함되므로,
     * 로비 참여자 또는 방장만 조회할 수 있도록 제한한다.
     *
     * [중요]
     * canStart는 조회 시점 snapshot 값이다.
     * 실제 게임 시작 가능 여부는 LobbyStartService에서 최종 검증한다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     * @return 로비 상세 응답
     */
    @Transactional(readOnly = true)
    public LobbyDetailResponse getLobbyDetail(String code, CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            log.warn("로비 상세 조회 거부 - principal 또는 userId가 null. code: {}", code);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        JoinLobbyResponse lobbyInfo = lobbyRepository.findByInviteCode(code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        String userIdentifier = principal.userIdentifier();

        if (!canAccessLobbyDetail(code, lobbyInfo, userIdentifier)) {
            log.warn(
                    "로비 상세 조회 거부 - 참여자가 아님. code: {}, userIdentifier: {}, hostId: {}",
                    code,
                    userIdentifier,
                    lobbyInfo.hostId()
            );
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_LOBBY_DETAIL_FORBIDDEN);
        }

        List<String> participantIdentifiers = includeHostIfMissing(
                lobbyRepository.getParticipantIdentifiers(code),
                lobbyInfo.hostId(),
                code
        );

        Set<String> readyParticipantIdentifiers = lobbyRepository.getReadyParticipantIdentifiers(code);

        List<LobbyPlayerResponse> players = participantIdentifiers.stream()
                .map(participantIdentifier -> toLobbyPlayerResponse(
                        participantIdentifier,
                        lobbyInfo.hostId(),
                        readyParticipantIdentifiers
                ))
                .toList();

        GameLobby gameLobby = gameLobbyJpaRepository.findByInviteCode(code)
                .orElse(null);

        boolean canStart = calculateCanStart(lobbyInfo, players, gameLobby);

        return LobbyDetailResponse.builder()
                .inviteCode(lobbyInfo.inviteCode())
                .title(lobbyInfo.title())
                .hostId(lobbyInfo.hostId())
                .maxPlayers(lobbyInfo.maxPlayers())
                .currentPlayers(Math.max(lobbyInfo.currentPlayers(), players.size()))
                .status(lobbyInfo.status())
                .mapId(lobbyInfo.mapId())
                .mapTitle(lobbyInfo.mapTitle())
                .mapCategory(lobbyInfo.mapCategory())
                .roundCount(gameLobby != null ? gameLobby.getRoundCount() : null)
                .timeLimitSeconds(gameLobby != null ? gameLobby.getTimeLimitSeconds() : null)
                .players(players)
                .canStart(canStart)
                .build();
    }

    /**
     * 요청자가 로비 방장인지 확인한다.
     *
     * Redis 로비 정보의 hostId는 userIdentifier 기준으로 저장되므로,
     * JWT principal의 userIdentifier와 직접 비교한다.
     */
    private boolean isLobbyHost(JoinLobbyResponse lobbyInfo, String userIdentifier) {
        return lobbyInfo.hostId() != null && lobbyInfo.hostId().equals(userIdentifier);
    }

    /**
     * 로비 상세 조회 권한을 확인한다.
     *
     * [정책]
     * - 방장은 participants Set 누락 여부와 관계없이 조회할 수 있다.
     * - 일반 유저는 WebSocket 구독으로 participants Set에 등록된 이후 조회할 수 있다.
     */
    private boolean canAccessLobbyDetail(
            String code,
            JoinLobbyResponse lobbyInfo,
            String userIdentifier
    ) {
        if (isLobbyHost(lobbyInfo, userIdentifier)) {
            return true;
        }

        return lobbyRepository.isParticipant(code, userIdentifier);
    }

    /**
     * 참여자 식별자를 로비 상세 응답용 플레이어 DTO로 변환한다.
     *
     * [방장 ready 정책]
     * 방장은 ready 대상에서 제외하므로 ready=false로 내려간다.
     * FE는 host=true 여부를 기준으로 ready 버튼을 숨김 처리한다.
     */
    private LobbyPlayerResponse toLobbyPlayerResponse(
            String participantIdentifier,
            String hostId,
            Set<String> readyParticipantIdentifiers
    ) {
        boolean host = hostId != null && hostId.equals(participantIdentifier);
        boolean ready = !host && readyParticipantIdentifiers.contains(participantIdentifier);

        return new LobbyPlayerResponse(
                participantIdentifier,
                host,
                ready
        );
    }

    /**
     * 로비 상세 응답의 canStart 값을 계산한다.
     *
     * [역할]
     * canStart는 FE의 게임 시작 버튼 활성화를 위한 조회 시점 snapshot 값이다.
     * 실제 게임 시작 가능 여부는 POST /api/lobbies/{code}/start에서
     * start_lobby.lua가 Redis 기준으로 최종 검증한다.
     *
     * [start_lobby.lua와 동일하게 맞추는 조건]
     * - 로비 상태가 WAITING이어야 한다.
     * - mapId가 존재해야 한다.
     * - 방장을 제외한 참여자가 1명 이상이어야 한다.
     * - 방장을 제외한 모든 참여자가 ready 상태여야 한다.
     *
     * [start_lobby.lua보다 Java에서 추가로 확인하는 조건]
     * - DB GAME_LOBBY 스냅샷이 존재해야 한다.
     * - 맵 문제 수가 roundCount 이상이어야 한다.
     *
     * [추후 리팩토링]
     * Issue #78 후속 단계에서 LobbyStartPolicy 또는 LobbyCanStartPolicy로 분리할 수 있습니다.
     */
    private boolean calculateCanStart(
            JoinLobbyResponse lobbyInfo,
            List<LobbyPlayerResponse> players,
            GameLobby gameLobby
    ) {
        if (!LOBBY_STATUS_WAITING.equals(lobbyInfo.status())) {
            return false;
        }

        if (lobbyInfo.mapId() == null) {
            return false;
        }

        if (!hasEnoughSongsForRound(gameLobby)) {
            return false;
        }

        List<LobbyPlayerResponse> nonHostPlayers = players.stream()
                .filter(player -> !player.host())
                .toList();

        if (nonHostPlayers.isEmpty()) {
            return false;
        }

        return nonHostPlayers.stream().allMatch(LobbyPlayerResponse::ready);
    }

    /**
     * 로비에 연결된 맵의 문제 수가 설정된 라운드 수 이상인지 확인한다.
     *
     * [필요 이유]
     * Data API 없이 저장된 맵 문제 수(numOfSong)를 기준으로 출제 가능 여부를 판단한다.
     * 이 조건은 실제 게임 시작 API에서도 검증하므로, canStart 계산에서도 동일하게 반영해야 한다.
     *
     * @param gameLobby DB에 저장된 로비 스냅샷
     * @return 맵 문제 수가 라운드 수 이상이면 true
     */
    private boolean hasEnoughSongsForRound(GameLobby gameLobby) {
        if (gameLobby == null || gameLobby.getMapId() == null || gameLobby.getRoundCount() == null) {
            return false;
        }

        return quizMapJpaRepository.findById(gameLobby.getMapId())
                .filter(quizMap -> !Boolean.TRUE.equals(quizMap.getIsDeleted()))
                .map(quizMap -> {
                    Integer numOfSong = quizMap.getNumOfSong();
                    return numOfSong != null && numOfSong >= gameLobby.getRoundCount();
                })
                .orElse(false);
    }

    /**
     * 로비 상세 응답에서 방장 정보가 누락되지 않도록 보정한다.
     *
     * [필요 이유]
     * 정상 흐름에서는 로비 생성 시 방장이 participants Set에 포함되어야 한다.
     * 다만 Redis 데이터 손상, 과거 데이터, WebSocket 입장 상태 불일치가 있으면 participants Set에서 방장이 누락될 수 있다.
     *
     * FE 대기실 UI는 players 목록의 host=true 값을 기준으로 방장 표시/시작 버튼/ready 버튼을 분기하므로,
     * 상세 응답에서는 hostId가 존재하면 players 목록에 방장을 보장한다.
     */
    private List<String> includeHostIfMissing(
            List<String> participantIdentifiers,
            String hostId,
            String code
    ) {
        if (hostId == null || hostId.isBlank() || participantIdentifiers.contains(hostId)) {
            return participantIdentifiers;
        }

        List<String> result = new ArrayList<>(participantIdentifiers);
        result.add(0, hostId);

        log.warn(
                "{} 로비 상세 응답 보정 - participants Set에 방장이 없어 응답 목록에 추가. "
                        + "code: {}, hostId: {}",
                LOG_ALERT_REQUIRED,
                code,
                hostId
        );

        return result;
    }
}