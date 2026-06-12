package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.PlaybackErrorReportDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundEndReason;
import io.github.ascrew.monomatbe.domain.game.dto.RoundSkipVoteDto;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 인게임 라운드 스킵 투표, 방장 강제 스킵, 재생 오류 fail-over를 처리하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameSkipVoteService {

    private static final Duration ROUND_SET_TTL = Duration.ofHours(2);
    private static final Set<String> SUPPORTED_YOUTUBE_IFRAME_ERROR_CODES = Set.of("2", "5", "100", "101", "150");

    private final StringRedisTemplate redisTemplate;
    private final LobbyRepository lobbyRepository;
    private final GameRoundEndService gameRoundEndService;
    private final GameRealtimeNotifier gameRealtimeNotifier;

    /**
     * 사용자의 스킵 투표를 기록하고 기준 도달 시 라운드를 종료한다.
     */
    public void voteSkip(String code, String userIdentifier, int roundNo) {
        if (!isValidPlayingParticipant(code, userIdentifier, roundNo)) {
            return;
        }

        VoteState voteState = addVoteAndReadState(
                RedisKeys.gameSessionRoundSkipVotesKey(code, roundNo),
                userIdentifier,
                code,
                false
        );
        if (voteState.totalParticipants() <= 0) {
            log.warn("스킵 투표 처리 불가 - 참가자 수 없음. code: {}, roundNo: {}", code, roundNo);
            return;
        }

        broadcastSkipVote(code, roundNo, voteState);

        if (voteState.reachedThreshold()) {
            log.info("스킵 투표 기준 도달 - code: {}, roundNo: {}, votes: {}, required: {}",
                    code, roundNo, voteState.votes(), voteState.requiredVotes());
            gameRoundEndService.endRound(code, roundNo, RoundEndReason.SKIP_VOTE);
        }
    }

    /**
     * 방장 명령이면 즉시 라운드를 종료한다.
     *
     * @return 방장 명령으로 처리했으면 true, 비방장이어서 명령으로 처리하지 않았으면 false
     */
    public boolean forceSkipByHost(String code, String userIdentifier, int roundNo) {
        if (!isValidPlayingParticipant(code, userIdentifier, roundNo)) {
            return true;
        }

        Object hostUserId = redisTemplate.opsForHash().get(RedisKeys.lobbyKey(code), RedisKeys.FIELD_HOST_USER_ID);
        if (!userIdentifier.equals(hostUserId)) {
            log.info("비방장 /p 입력 - 라운드 종료 없이 무시. code: {}, user: {}", code, userIdentifier);
            return false;
        }

        log.info("방장 강제 스킵 처리 - code: {}, roundNo: {}, host: {}", code, roundNo, userIdentifier);
        gameRoundEndService.endRound(code, roundNo, RoundEndReason.HOST_SKIP);
        return true;
    }

    /**
     * 클라이언트 재생 오류 보고를 기록하고 기준 도달 시 fail-over 스킵한다.
     */
    public void reportPlaybackError(String code, String userIdentifier, PlaybackErrorReportDto request) {
        if (request == null || request.roundNo() == null) {
            log.warn("재생 오류 보고 무시 - 잘못된 요청. code: {}, user: {}", code, userIdentifier);
            return;
        }

        int roundNo = request.roundNo();
        if (!isValidPlayingParticipant(code, userIdentifier, roundNo)) {
            return;
        }
        if (!isSupportedPlaybackErrorCode(request.errorCode())) {
            log.warn("재생 오류 보고 무시 - 지원하지 않는 errorCode. code: {}, user: {}, errorCode: {}",
                    code, userIdentifier, request.errorCode());
            return;
        }

        VoteState voteState = addVoteAndReadState(
                RedisKeys.gameSessionRoundPlaybackErrorsKey(code, roundNo),
                userIdentifier,
                code,
                true
        );
        if (voteState.totalParticipants() <= 0) {
            log.warn("재생 오류 보고 처리 불가 - 참가자 수 없음. code: {}, roundNo: {}", code, roundNo);
            return;
        }

        if (voteState.reachedThreshold()) {
            log.error("[MONITORING_REQUIRED] 재생 오류 보고 기준 도달로 라운드 자동 스킵 - "
                            + "code: {}, roundNo: {}, reports: {}, required: {}, errorCode: {}, message: {}",
                    code,
                    roundNo,
                    voteState.votes(),
                    voteState.requiredVotes(),
                    request.errorCode(),
                    request.message());
            gameRoundEndService.endRound(code, roundNo, RoundEndReason.PLAYBACK_ERROR);
        }
    }

    /**
     * 참가자 퇴장 후 현재 누적된 스킵/오류 보고가 새 참가자 수 기준에 도달했는지 재평가한다.
     */
    public void reevaluateSkipThresholds(String code, int roundNo) {
        if (!hasActivePlayingRound(code, roundNo)) {
            return;
        }

        VoteState skipState = readVoteState(RedisKeys.gameSessionRoundSkipVotesKey(code, roundNo), code, false);
        if (skipState.reachedThreshold()) {
            log.info("참가자 변경 후 스킵 투표 기준 도달 - code: {}, roundNo: {}, votes: {}, required: {}",
                    code, roundNo, skipState.votes(), skipState.requiredVotes());
            gameRoundEndService.endRound(code, roundNo, RoundEndReason.SKIP_VOTE);
            return;
        }

        VoteState playbackErrorState = readVoteState(RedisKeys.gameSessionRoundPlaybackErrorsKey(code, roundNo), code, true);
        if (playbackErrorState.reachedThreshold()) {
            log.error("[MONITORING_REQUIRED] 참가자 변경 후 재생 오류 보고 기준 도달로 라운드 자동 스킵 - "
                            + "code: {}, roundNo: {}, reports: {}, required: {}",
                    code, roundNo, playbackErrorState.votes(), playbackErrorState.requiredVotes());
            gameRoundEndService.endRound(code, roundNo, RoundEndReason.PLAYBACK_ERROR);
        }
    }

    /**
     * 퇴장한 참가자의 라운드별 스킵/오류 신호를 제거한다.
     */
    public void removeParticipantRoundSignals(String code, String userIdentifier, int roundNo) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier) || roundNo <= 0) {
            return;
        }

        redisTemplate.opsForSet().remove(RedisKeys.gameSessionRoundSkipVotesKey(code, roundNo), userIdentifier);
        redisTemplate.opsForSet().remove(RedisKeys.gameSessionRoundPlaybackErrorsKey(code, roundNo), userIdentifier);
    }

    private VoteState addVoteAndReadState(String voteKey, String userIdentifier, String code, boolean playbackError) {
        redisTemplate.opsForSet().add(voteKey, userIdentifier);
        redisTemplate.expire(voteKey, ROUND_SET_TTL);
        return readVoteState(voteKey, code, playbackError);
    }

    private VoteState readVoteState(String voteKey, String code, boolean playbackError) {
        Set<String> participants = redisTemplate.opsForSet().members(RedisKeys.lobbyParticipantsKey(code));
        Set<String> voteMembers = redisTemplate.opsForSet().members(voteKey);
        Set<String> activeParticipants = participants != null ? participants : Collections.emptySet();
        Set<String> activeVotes = new HashSet<>(voteMembers != null ? voteMembers : Collections.emptySet());
        activeVotes.retainAll(activeParticipants);

        long total = activeParticipants.size();
        long required = playbackError ? requiredPlaybackErrorReports(total) : requiredVotes(total);
        return new VoteState(activeVotes.size(), required, total);
    }

    private void broadcastSkipVote(String code, int roundNo, VoteState voteState) {
        RoundSkipVoteDto dto = RoundSkipVoteDto.builder()
                .roundNo(roundNo)
                .votes(voteState.votes())
                .requiredVotes(voteState.requiredVotes())
                .totalParticipants(voteState.totalParticipants())
                .build();
        gameRealtimeNotifier.notifyRoundSkipVote(code, dto);
    }

    private boolean isValidPlayingParticipant(String code, String userIdentifier, int roundNo) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier) || roundNo <= 0) {
            return false;
        }
        if (!lobbyRepository.existsByCode(code)) {
            log.warn("스킵 처리 무시 - 존재하지 않는 로비. code: {}", code);
            return false;
        }
        if (!lobbyRepository.isParticipant(code, userIdentifier)) {
            log.warn("스킵 처리 무시 - 로비 참여자가 아님. code: {}, user: {}", code, userIdentifier);
            return false;
        }
        return hasActivePlayingRound(code, roundNo);
    }

    private boolean hasActivePlayingRound(String code, int roundNo) {
        String sessionKey = RedisKeys.gameSessionKey(code);
        List<Object> values = redisTemplate.opsForHash().multiGet(sessionKey, List.of(
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE,
                RedisKeys.FIELD_CURRENT_ROUND_NO
        ));
        if (values == null || values.size() < 3 || values.get(0) == null) {
            log.warn("스킵 처리 무시 - 활성화된 게임 세션이 없음. code: {}", code);
            return false;
        }

        String status = (String) values.get(0);
        String roundPhase = (String) values.get(1);
        String currentRoundNoStr = (String) values.get(2);
        if (!"PLAYING".equals(status) || !"PLAYING".equals(roundPhase) || currentRoundNoStr == null) {
            log.warn("스킵 처리 무시 - 게임 진행 중이 아님. code: {}, status: {}, phase: {}",
                    code, status, roundPhase);
            return false;
        }

        int currentRoundNo;
        try {
            currentRoundNo = Integer.parseInt(currentRoundNoStr);
        } catch (NumberFormatException e) {
            log.warn("스킵 처리 무시 - 현재 라운드 번호 파싱 실패. code: {}, value: {}", code, currentRoundNoStr);
            return false;
        }
        if (roundNo != currentRoundNo) {
            log.warn("스킵 처리 무시 - 라운드 번호 불일치. code: {}, expected: {}, actual: {}",
                    code, currentRoundNo, roundNo);
            return false;
        }
        return true;
    }

    private boolean isSupportedPlaybackErrorCode(String errorCode) {
        return StringUtils.hasText(errorCode) && SUPPORTED_YOUTUBE_IFRAME_ERROR_CODES.contains(errorCode.trim());
    }

    private long requiredVotes(long totalParticipants) {
        if (totalParticipants <= 0) {
            return 0;
        }
        return (totalParticipants + 1) / 2;
    }

    private long requiredPlaybackErrorReports(long totalParticipants) {
        if (totalParticipants <= 1) {
            return totalParticipants;
        }
        return Math.max(2, (totalParticipants + 1) / 2);
    }

    private record VoteState(long votes, long requiredVotes, long totalParticipants) {
        boolean reachedThreshold() {
            return totalParticipants > 0 && votes >= requiredVotes;
        }
    }
}
