package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionPlayerJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

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
    public RoundStartDto createGameSession(GameLobby lobby) {
        String code = lobby.getInviteCode();

        // 1. 문제 데이터 로드 및 셔플링
        List<MapItem> mapItems = mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(lobby.getMapId());
        Collections.shuffle(mapItems);
        List<MapItem> selectedItems = mapItems.stream()
                .limit(lobby.getRoundCount())
                .toList();

        if (selectedItems.size() < lobby.getRoundCount()) {
            throw new IllegalStateException("출제 가능한 문제 수가 라운드 수보다 적습니다.");
        }

        // 2. DB 세션 생성
        GameSession gameSession = GameSession.builder()
                .lobby(lobby)
                .currentRoundNo(1)
                .totalRoundCount(lobby.getRoundCount())
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

        long serverStartedAt = System.currentTimeMillis();

        String result = redisTemplate.execute(
                initGameSessionScript,
                List.of(sessionKey, roundsKey, playersKey),
                String.valueOf(lobby.getRoundCount()),
                mapItemIdsStr,
                participantsStr,
                String.valueOf(lobby.getTimeLimitSeconds()),
                String.valueOf(serverStartedAt)
        );

        if (!"OK".equals(result)) {
            throw new IllegalStateException("게임 세션 Redis 초기화 실패: " + result);
        }

        log.info("게임 세션 생성 완료 - 로비 코드: {}, 라운드 수: {}, 참여자 수: {}", 
                 code, lobby.getRoundCount(), participantIdentifiers.size());

        MapItem firstItem = selectedItems.get(0);
        return RoundStartDto.builder()
                .type("ROUND_STARTED")
                .videoId(firstItem.getVideoId())
                .youtubeUrl(firstItem.getYoutubeUrl())
                .startTime(firstItem.getStartTime())
                .endTime(firstItem.getEndTime())
                .timeLimitSeconds(lobby.getTimeLimitSeconds())
                .roundNo(1)
                .serverStartedAt(serverStartedAt)
                .build();
    }
}
