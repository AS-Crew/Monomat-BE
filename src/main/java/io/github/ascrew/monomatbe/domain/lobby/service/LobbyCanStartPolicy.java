/*
 * 로비 상세 응답의 canStart 계산 정책을 담당하는 클래스
 *
 * [책임]
 * - 조회 시점 기준으로 게임 시작 버튼 활성화 가능 여부를 계산
 * - WAITING 상태 확인
 * - mapId 존재 여부 확인
 * - 맵 문제 수가 roundCount 이상인지 확인
 * - 방장을 제외한 참여자가 1명 이상인지 확인
 * - 방장을 제외한 모든 참여자가 ready 상태인지 확인
 *
 * [중요]
 * 이 클래스는 실제 게임 시작 가능 여부를 최종 확정하지 않는다.
 *
 * canStart는 FE 버튼 활성화를 위한 snapshot 값이다.
 * 실제 시작 가능 여부는 POST /api/lobbies/{code}/start 요청 시
 * LobbyStartService와 start_lobby.lua에서 최종 검증한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPlayerResponse;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LobbyCanStartPolicy {

    /**
     * 조회 시점 기준 시작 가능 상태
     * Redis Hash에서 읽은 status 문자열과 비교하므로 name() 값으로 관리한다.
     */
    private static final String LOBBY_STATUS_WAITING = LobbyStatus.WAITING.name();

    private final QuizMapJpaRepository quizMapJpaRepository;

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
     * [의도적으로 완전히 동일하지 않은 부분]
     * start_lobby.lua는 게임 시작 직전에 participants Set의 stale participant 여부를
     * user_session 키로 한 번 더 검증한다.
     *
     * canStart는 상세 조회 시점 snapshot이므로 stale participant까지 완전히 확정하지 않는다.
     * 따라서 canStart=true여도 사용자가 퇴장/ready 해제/세션 만료를 겪으면 /start는 409 Conflict로 실패할 수 있다.
     *
     * @param lobbyInfo Redis에서 조회한 로비 기본 정보
     * @param players   로비 상세 응답용 플레이어 목록
     * @param gameLobby DB에 저장된 GAME_LOBBY 스냅샷
     * @return 조회 시점 기준 시작 버튼을 활성화할 수 있으면 true
     */
    public boolean calculateCanStart(
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
     * Data API 없이 저장된 맵 문제 수(numOfSong)를 기준으로 출제 가능 여부를 판단한다/
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
}