package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.domain.game.dto.PlayerRankingDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundMetadataDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionPlayerJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyRealtimeNotifier;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameRoundEndService {

    private final StringRedisTemplate redisTemplate;
    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final GameSessionPlayerJpaRepository gameSessionPlayerJpaRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final UserSessionRepository userSessionRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final LobbyPlayerNicknameResolver nicknameResolver;
    private final GameRealtimeNotifier gameRealtimeNotifier;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;
    private final JsonMapper jsonMapper;
    private final ApplicationContext applicationContext;

    /**
     * 특정 라운드를 종료하고 점수 계산 및 DB/Redis 업데이트를 수행합니다.
     */
    @Transactional
    public void endRound(String lobbyCode, int roundNo) {
        String endedLockKey = RedisKeys.gameSessionRoundEndedLockKey(lobbyCode, roundNo);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(endedLockKey, "1", Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.info("라운드 종료 무시됨 (이미 종료 처리됨) - code: {}, roundNo: {}", lobbyCode, roundNo);
            return;
        }

        log.info("라운드 종료 처리 시작 - code: {}, roundNo: {}", lobbyCode, roundNo);

        // 1. 게임 세션 및 플레이어 조회
        GameSession gameSession = gameSessionJpaRepository.findActiveSessionByLobbyCode(lobbyCode)
                .orElseThrow(() -> new NoSuchElementException("게임 세션을 찾을 수 없습니다. code: " + lobbyCode));

        List<GameSessionPlayer> dbPlayers = gameSessionPlayerJpaRepository.findAllByGameSessionId(gameSession.getId());

        // 2. Redis에서 정답자 및 1등 정보 조회
        String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(lobbyCode, roundNo);
        Set<String> correctPlayerIdentifiers = redisTemplate.opsForSet().members(correctPlayersKey);
        if (correctPlayerIdentifiers == null) {
            correctPlayerIdentifiers = Collections.emptySet();
        }

        String roundDataKey = RedisKeys.gameSessionRoundDataKey(lobbyCode, roundNo);
        String firstCorrectIdentifier = (String) redisTemplate.opsForHash().get(roundDataKey, "first_correct_user_id");

        // 3. userIdentifier -> User 매핑 정보 빌드
        String playersKey = RedisKeys.gameSessionPlayersKey(lobbyCode);
        Map<Object, Object> playersMap = redisTemplate.opsForHash().entries(playersKey);
        Set<String> participantIdentifiers = playersMap.keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());

        Map<Long, String> userIdToIdentifier = new HashMap<>();
        userSessionRepository.findBySessionIdIn(participantIdentifiers)
                .forEach(s -> userIdToIdentifier.put(s.getUser().getId(), s.getSessionId()));
        guestSessionRepository.findByGuestTokenIn(participantIdentifiers)
                .forEach(g -> userIdToIdentifier.put(g.getUser().getId(), g.getGuestToken()));

        // 4. 각 플레이어별 득점 계산 및 반영
        Map<String, Integer> scoreAddedMap = new HashMap<>();
        for (GameSessionPlayer dbPlayer : dbPlayers) {
            String identifier = userIdToIdentifier.get(dbPlayer.getUser().getId());
            if (identifier == null) {
                continue;
            }

            int scoreAdded = 0;
            if (correctPlayerIdentifiers.contains(identifier)) {
                scoreAdded += 100; // 기본 점수
                if (identifier.equals(firstCorrectIdentifier)) {
                    scoreAdded += 40; // 1등 보너스
                }
            }

            scoreAddedMap.put(identifier, scoreAdded);

            if (scoreAdded > 0) {
                dbPlayer.addScore(scoreAdded);
                gameSessionPlayerJpaRepository.save(dbPlayer);
            }
        }

        // 5. 실시간 랭킹 산정
        Map<String, String> nicknameMap = nicknameResolver.resolveNicknameMap(participantIdentifiers);
        List<PlayerTemp> tempPlayers = new ArrayList<>();
        for (GameSessionPlayer dbPlayer : dbPlayers) {
            String identifier = userIdToIdentifier.get(dbPlayer.getUser().getId());
            if (identifier == null) {
                continue;
            }
            String nickname = nicknameMap.getOrDefault(identifier, nicknameResolver.fallbackNickname(identifier));
            tempPlayers.add(new PlayerTemp(identifier, nickname, dbPlayer.getScore(), scoreAddedMap.getOrDefault(identifier, 0)));
        }

        // 점수 기준 내림차순 정렬
        tempPlayers.sort((p1, p2) -> Integer.compare(p2.totalScore, p1.totalScore));

        List<PlayerRankingDto> rankings = new ArrayList<>();
        int currentRank = 1;
        for (int i = 0; i < tempPlayers.size(); i++) {
            PlayerTemp p = tempPlayers.get(i);
            if (i > 0 && p.totalScore < tempPlayers.get(i - 1).totalScore) {
                currentRank = i + 1;
            }
            rankings.add(PlayerRankingDto.builder()
                    .userIdentifier(p.userIdentifier)
                    .nickname(p.nickname)
                    .score(p.totalScore)
                    .rank(currentRank)
                    .scoreAdded(p.scoreAdded)
                    .build());
        }

        // 6. 라운드 종료 메타데이터 구성 (곡 정보)
        String roundsKey = RedisKeys.gameSessionRoundsKey(lobbyCode);
        String mapItemIdStr = redisTemplate.opsForList().index(roundsKey, roundNo - 1);
        String title = "";
        String artist = "";
        String representativeAnswer = "";
        String thumbnailUrl = "";

        if (mapItemIdStr != null) {
            try {
                Long mapItemId = Long.parseLong(mapItemIdStr);
                MapItem mapItem = mapItemJpaRepository.findById(mapItemId).orElse(null);
                if (mapItem != null) {
                    title = mapItem.getTitle();
                    artist = mapItem.getArtist();
                    thumbnailUrl = mapItem.getThumbnailUrl();
                    List<String> rawAnswers = jsonMapper.readValue(mapItem.getAnswers(), new TypeReference<List<String>>() {});
                    representativeAnswer = rawAnswers.isEmpty() ? title : rawAnswers.get(0);
                }
            } catch (Exception e) {
                log.error("라운드 종료 메타데이터 파싱 실패 - code: {}, roundNo: {}", lobbyCode, roundNo, e);
            }
        }

        RoundMetadataDto metadataDto = RoundMetadataDto.builder()
                .type("ROUND_END")
                .title(title)
                .artist(artist)
                .answer(representativeAnswer)
                .thumbnailUrl(thumbnailUrl)
                .rankings(rankings)
                .build();

        // 7. 다음 라운드 또는 게임 종료 전환
        boolean isLastRound = roundNo >= gameSession.getTotalQuestionCount();

        if (isLastRound) {
            gameSession.finish();
            gameSessionJpaRepository.save(gameSession);

            GameLobby lobby = gameSession.getLobby();
            lobby.changeStatus(LobbyStatus.FINISHED);
            gameLobbyJpaRepository.save(lobby);

            redisTemplate.opsForHash().put(RedisKeys.gameSessionKey(lobbyCode), "status", "FINISHED");
            redisTemplate.opsForHash().put(RedisKeys.lobbyKey(lobbyCode), "status", "FINISHED");
        }

        // 8. 트랜잭션 성공 후 STOMP 브로드캐스트 및 스케줄링 등록
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("라운드 종료 트랜잭션 커밋 완료 - Redis 점수 반영 및 브로드캐스트 전송. code: {}, roundNo: {}, isLast: {}", lobbyCode, roundNo, isLastRound);
                
                scoreAddedMap.forEach((identifier, scoreAdded) -> {
                    if (scoreAdded > 0) {
                        redisTemplate.opsForHash().increment(playersKey, identifier, scoreAdded);
                    }
                });

                gameRealtimeNotifier.notifyRoundEnd(lobbyCode, metadataDto);

                GameRoundProgressService progressService = applicationContext.getBean(GameRoundProgressService.class);
                if (isLastRound) {
                    try {
                        lobbyRealtimeNotifier.notifyLobbyInfoRefresh(lobbyCode, "SYSTEM");
                    } catch (Exception e) {
                        log.error("게임 종료 후 로비 갱신 알림 실패 - code: {}", lobbyCode, e);
                    }
                } else {
                    progressService.scheduleNextRound(lobbyCode, roundNo + 1);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("라운드 종료 트랜잭션 롤백 감지 - 멱등 락 해제. code: {}, roundNo: {}", lobbyCode, roundNo);
                    redisTemplate.delete(endedLockKey);
                }
            }
        });
    }

    private static class PlayerTemp {
        String userIdentifier;
        String nickname;
        int totalScore;
        int scoreAdded;

        PlayerTemp(String userIdentifier, String nickname, int totalScore, int scoreAdded) {
            this.userIdentifier = userIdentifier;
            this.nickname = nickname;
            this.totalScore = totalScore;
            this.scoreAdded = scoreAdded;
        }
    }
}
