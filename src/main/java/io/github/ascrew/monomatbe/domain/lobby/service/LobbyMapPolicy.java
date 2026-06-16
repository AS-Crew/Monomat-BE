/*
 * 로비에서 사용할 맵 접근 정책을 담당하는 클래스
 *
 * [책임]
 * - 로비 생성 시 선택된 mapId 검증
 * - 맵 존재 여부 검증
 * - 삭제된 맵 사용 차단
 * - 공개 맵 또는 본인 소유 비공개 맵만 사용 허용
 * - Redis 저장 및 라운드 수 계산에 필요한 LobbyMapMetadata 생성
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class LobbyMapPolicy {

    private static final String ERROR_MAP_NOT_FOUND =
            "존재하지 않는 맵입니다.";
    private static final String ERROR_MAP_DELETED =
            "삭제된 맵은 로비에 연결할 수 없습니다.";
    private static final String ERROR_PRIVATE_MAP_FORBIDDEN =
            "비공개 맵은 소유자만 로비에 연결할 수 있습니다.";
    private static final String ERROR_MAP_HAS_NO_SONG =
            "등록된 곡이 없는 맵은 로비에 연결할 수 없습니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;

    /**
     * 로비에 연결할 맵 메타데이터를 검증 후 반환한다.
     *
     * [정책]
     * - mapId가 null이면 맵 미선택 로비로 보고 null을 반환한다.
     * - mapId가 있으면 DB에서 맵을 조회한다.
     * - 존재하지 않는 맵은 404로 차단한다.
     * - 삭제된 맵은 409로 차단한다.
     * - 공개 맵은 누구나 사용할 수 있다.
     * - 비공개 맵은 소유자만 사용할 수 있다.
     * - 등록 곡 수가 1개 미만인 맵은 로비에 연결할 수 없다.
     *
     * @param mapId           요청으로 전달된 맵 ID
     * @param requesterUserId 로비 생성 또는 설정 변경 요청자의 DB userId
     * @return 선택된 맵 메타데이터. 맵 미선택 시 null.
     */
    public LobbyMapMetadata resolveLobbyMapMetadata(Long mapId, Long requesterUserId) {
        if (mapId == null) {
            return null;
        }

        QuizMap quizMap = quizMapJpaRepository.findById(mapId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_MAP_NOT_FOUND
                ));

        validateUsableMap(quizMap, requesterUserId);
        validateHasSong(quizMap);

        return new LobbyMapMetadata(
                quizMap.getId(),
                quizMap.getTitle(),
                quizMap.getCategory().value(),
                quizMap.getNumOfSong()
        );
    }

    private void validateUsableMap(QuizMap quizMap, Long requesterUserId) {
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
    }

    private void validateHasSong(QuizMap quizMap) {
        Integer numOfSong = quizMap.getNumOfSong();

        if (numOfSong == null || numOfSong < LobbyDefaults.MIN_QUESTION_COUNT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_MAP_HAS_NO_SONG
            );
        }
    }

    private boolean canUseMap(QuizMap quizMap, Long requesterUserId) {
        if (Boolean.TRUE.equals(quizMap.getIsPublic())) {
            return true;
        }

        return quizMap.getOwner() != null
                && quizMap.getOwner().getId() != null
                && quizMap.getOwner().getId().equals(requesterUserId);
    }
}