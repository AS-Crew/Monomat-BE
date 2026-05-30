/*
 * 로비 게임 시작 전 검증 정책을 담당하는 클래스
 *
 * [책임]
 * - 게임 시작 시 로비에 mapId가 선택되어 있는지 검증
 * - 선택된 맵이 존재하는지 검증
 * - 삭제된 맵인지 검증
 * - 맵 문제 수가 로비 roundCount 이상인지 검증
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class LobbyStartPolicy {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_START_MAP_NOT_SELECTED =
            "게임을 시작하려면 맵을 선택해야 합니다.";
    private static final String ERROR_MAP_NOT_FOUND =
            "존재하지 않는 맵입니다.";
    private static final String ERROR_MAP_DELETED =
            "삭제된 맵은 로비에 연결할 수 없습니다.";
    private static final String ERROR_START_MAP_SONG_COUNT_NOT_ENOUGH =
            "맵의 문제 수가 설정된 라운드 수보다 적습니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;

    /**
     * 게임 시작에 사용할 맵을 검증하고 반환한다.
     *
     * [검증]
     * - 로비에 mapId가 있어야 한다.
     * - mapId에 해당하는 맵이 존재해야 한다.
     * - 삭제된 맵이면 시작할 수 없다.
     * - 맵 문제 수가 로비 roundCount 이상이어야 한다.
     *
     * [반환 이유]
     * LobbyStartService는 시작 완료 로그에 mapId를 남기고 있다.
     * 따라서 검증된 QuizMap을 반환하여 중복 조회를 피한다.
     *
     * @param gameLobby DB에 저장된 로비 스냅샷
     * @return 검증이 완료된 QuizMap
     */
    public QuizMap validateStartableMap(GameLobby gameLobby) {
        QuizMap quizMap = resolveStartMap(gameLobby);
        validateMapSongCount(quizMap, gameLobby);
        return quizMap;
    }

    /**
     * 게임 시작에 사용할 맵을 조회한다.
     *
     * @param gameLobby DB에 저장된 로비 스냅샷
     * @return 시작에 사용할 맵
     */
    private QuizMap resolveStartMap(GameLobby gameLobby) {
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
     * [검증 기준]
     * Data API 없이 게임을 운영하므로, 실제 출제 가능 여부는 맵에 저장된 문제 수(numOfSong)를 기준으로 판단한다.
     *
     * @param quizMap   검증 대상 맵
     * @param gameLobby DB에 저장된 로비 스냅샷
     */
    private void validateMapSongCount(QuizMap quizMap, GameLobby gameLobby) {
        Integer numOfSong = quizMap.getNumOfSong();
        Integer questionCount = gameLobby.getQuestionCount();

        if (numOfSong == null || questionCount == null || numOfSong < questionCount) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_MAP_SONG_COUNT_NOT_ENOUGH
            );
        }
    }
}