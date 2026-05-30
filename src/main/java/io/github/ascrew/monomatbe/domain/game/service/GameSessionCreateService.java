package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionPlayerJpaRepository;
import io.github.ascrew.monomatbe.domain.game.exception.GameSessionAlreadyExistsException;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 인게임 세션 생성을 담당하는 서비스.
 *
 * [책임]
 * - DB GAME_SESSION, GAME_SESSION_PLAYER 스냅샷 생성
 * - Redis 게임 세션 초기화 (Lua Script)
 * - MapItem 조회 및 라운드 생성 (셔플링 포함)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameSessionCreateService {

    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final GameSessionPlayerJpaRepository gameSessionPlayerJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final LobbyRepository lobbyRepository;
    private final GameParticipantResolver gameParticipantResolver;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> initGameSessionScript;

    /**
     * 로비 게임 시작 시 호출되어 게임 세션을 초기화한다.
     * 트랜잭션 내에서 실행되므로, 이 과정 중 예외가 발생하면 로비 상태 변경도 롤백된다.
     */
    public RoundStartDto createGameSession(GameLobby lobby, QuizMap map) {
        String code = lobby.getInviteCode();

        // 1. 문제 데이터 로드 및 셔플링
        List<MapItem> mapItems = mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(map.getId());
        Collections.shuffle(mapItems);
        List<MapItem> selectedItems = mapItems.stream()
                .limit(lobby.getQuestionCount())
                .toList();

        if (selectedItems.size() < lobby.getQuestionCount()) {
            throw new io.github.ascrew.monomatbe.domain.game.exception.NotEnoughMapItemsException("출제 가능한 문제 수가 설정된 문제 갯수보다 적습니다.");
        }

        long serverStartedAt = System.currentTimeMillis();
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(serverStartedAt), java.time.ZoneId.systemDefault());

        // 2. DB 세션 생성
        GameSession gameSession = GameSession.builder()
                .lobby(lobby)
                .map(map)
                .currentRoundNo(1)
                .totalQuestionCount(lobby.getQuestionCount())
                .startedAt(startedAt)
                .build();
        gameSessionJpaRepository.save(gameSession);

        // 3. DB 플레이어 스냅샷 생성
        List<String> participantIdentifiers = lobbyRepository.getParticipantIdentifiers(code);
        List<User> participants = gameParticipantResolver.resolveUsers(participantIdentifiers);

        if (participants.size() != participantIdentifiers.size()) {
            throw new IllegalStateException("게임 참가자 스냅샷 수가 Redis 참가자 수와 일치하지 않습니다.");
        }

        List<GameSessionPlayer> players = participants.stream()
                .map(user -> GameSessionPlayer.builder()
                        .gameSession(gameSession)
                        .user(user)
                        .score(0)
                        .build())
                .toList();
        gameSessionPlayerJpaRepository.saveAll(players);

        // 4. Redis 초기화 (Lua)
        String sessionKey = RedisKeys.gameSessionKey(code);
        String roundsKey = RedisKeys.gameSessionRoundsKey(code);
        String playersKey = RedisKeys.gameSessionPlayersKey(code);

        String mapItemIdsStr = selectedItems.stream()
                .map(item -> String.valueOf(item.getId()))
                .collect(Collectors.joining(","));
        String participantsStr = String.join(",", participantIdentifiers);

        String result = redisTemplate.execute(
                initGameSessionScript,
                List.of(sessionKey, roundsKey, playersKey),
                String.valueOf(lobby.getQuestionCount()),
                mapItemIdsStr,
                participantsStr,
                String.valueOf(lobby.getTimeLimitSeconds()),
                String.valueOf(serverStartedAt),
                String.valueOf(7200)
        );

        if ("ERROR_ALREADY_EXISTS".equals(result)) {
            throw new GameSessionAlreadyExistsException("게임 세션이 이미 존재합니다.");
        }
        if (!"OK".equals(result)) {
            throw new IllegalStateException("게임 세션 Redis 초기화 실패: " + result);
        }

        log.info("게임 세션 생성 완료 - 로비 코드: {}, 문제 갯수: {}, 참여자 수: {}",
                 code, lobby.getQuestionCount(), participantIdentifiers.size());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("DB 트랜잭션 롤백 감지 - Redis 세션 잔여 데이터 정리. code: {}", code);
                    redisTemplate.delete(List.of(sessionKey, roundsKey, playersKey));
                }
            }
        });

        MapItem firstItem = selectedItems.get(0);
        int effectiveEndTime = firstItem.getStartTime() + lobby.getTimeLimitSeconds();
        return RoundStartDto.builder()
                .type("ROUND_READY")
                .videoId(firstItem.getVideoId())
                .youtubeUrl(firstItem.getYoutubeUrl())
                .startTime(firstItem.getStartTime())
                .endTime(effectiveEndTime)
                .timeLimitSeconds(lobby.getTimeLimitSeconds())
                .roundNo(1)
                .serverStartedAt(serverStartedAt)
                .build();
    }
}
