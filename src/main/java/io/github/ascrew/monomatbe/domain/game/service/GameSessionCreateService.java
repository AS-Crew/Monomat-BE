package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
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
    private final UserSessionRepository userSessionRepository;
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
        for (String userIdentifier : participantIdentifiers) {
            userSessionRepository.findBySessionId(userIdentifier).ifPresent(userSession -> {
                User user = userSession.getUser();
                GameSessionPlayer player = GameSessionPlayer.builder()
                        .gameSession(gameSession)
                        .user(user)
                        .score(0)
                        .build();
                gameSessionPlayerJpaRepository.save(player);
            });
        }

        // 4. Redis 초기화 (Lua)
        String sessionKey = RedisKeys.gameSessionKey(code);
        String roundsKey = RedisKeys.gameSessionRoundsKey(code);
        String playersKey = RedisKeys.gameSessionPlayersKey(code);

        String mapItemIdsStr = selectedItems.stream()
                .map(item -> String.valueOf(item.getId()))
                .collect(Collectors.joining(","));
        String participantsStr = String.join(",", participantIdentifiers);

        redisTemplate.execute(
                initGameSessionScript,
                List.of(sessionKey, roundsKey, playersKey),
                String.valueOf(lobby.getRoundCount()),
                mapItemIdsStr,
                participantsStr
        );

        log.info("게임 세션 생성 완료 - 로비 코드: {}, 라운드 수: {}, 참여자 수: {}", 
                 code, lobby.getRoundCount(), participantIdentifiers.size());

        MapItem firstItem = selectedItems.get(0);
        return RoundStartDto.builder()
                .videoId(firstItem.getVideoId())
                .startTime(firstItem.getStartTime())
                .endTime(firstItem.getEndTime())
                .roundNo(1)
                .serverTime(System.currentTimeMillis())
                .build();
    }
}
