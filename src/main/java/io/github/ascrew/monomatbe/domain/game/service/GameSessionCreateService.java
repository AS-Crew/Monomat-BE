package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.config.GameSessionProperties;
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
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import io.github.ascrew.monomatbe.domain.map.support.AnswerNormalizer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final JsonMapper jsonMapper;
    private final GameSessionCleanupService gameSessionCleanupService;
    private final GameSessionProperties gameSessionProperties;

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
        // WAS OS 로컬 타임존 의존을 피하기 위해 UTC로 고정한다. (코드베이스 타임스탬프 관례와 일치)
        LocalDateTime startedAt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(serverStartedAt), ZoneOffset.UTC);

        // 0. 기존 미종료 활성 세션 처리: 기본은 차단, 정체(stale)된 세션만 복구한다.
        gameSessionJpaRepository.findActiveSessionByLobbyCode(code)
                .ifPresent(activeSession -> {
                    if (activeSession.isStale(startedAt, gameSessionProperties.getStaleThreshold())) {
                        log.warn("정체(stale) 게임 세션 복구 후 재시작 허용 - code: {}, oldSessionId: {}, startedAt: {}",
                                code, activeSession.getId(), activeSession.getStartedAt());
                        activeSession.finish();
                        // Redis 잔존 세션 키를 즉시 제거해 이후 init Lua의 ERROR_ALREADY_EXISTS를 방지한다.
                        gameSessionCleanupService.deleteNow(code);
                    } else {
                        // 실제 진행 중인 게임을 중복 시작 요청으로부터 보호한다.
                        throw new GameSessionAlreadyExistsException("이미 진행 중인 게임 세션이 있습니다.");
                    }
                });

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

        // 4-1. 라운드별 문제 데이터 캐싱 데이터 구성 (JSON 정규화는 파이프라인 밖에서 수행)
        List<RoundCacheEntry> roundCacheEntries = new ArrayList<>();
        for (int i = 0; i < selectedItems.size(); i++) {
            MapItem item = selectedItems.get(i);
            int roundNo = i + 1;
            Map<String, String> roundData = new java.util.HashMap<>();
            roundData.put("answers", item.getAnswers());

            // 정규화된 정답 목록 캐싱 추가
            try {
                List<String> rawAnswers = jsonMapper.readValue(item.getAnswers(), new TypeReference<List<String>>() {});
                List<String> normalizedAnswers = rawAnswers.stream()
                        .map(AnswerNormalizer::normalize)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                roundData.put("normalized_answers", jsonMapper.writeValueAsString(normalizedAnswers));
            } catch (Exception e) {
                log.error("createGameSession: 정답 데이터 정규화 캐싱 실패 - item id: {}", item.getId(), e);
                roundData.put("normalized_answers", "[]");
            }

            roundData.put("title", item.getTitle() == null ? "" : item.getTitle());
            roundData.put("artist", item.getArtist() == null ? "" : item.getArtist());
            roundCacheEntries.add(new RoundCacheEntry(RedisKeys.gameSessionRoundDataKey(code, roundNo), roundData));
        }

        // putAll + expire를 단일 파이프라인으로 일괄 전송해 라운드당 2 RTT 누적(N라운드 = 2N RTT)을 줄인다.
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection stringConnection = (StringRedisConnection) connection;
            for (RoundCacheEntry entry : roundCacheEntries) {
                stringConnection.hMSet(entry.key(), entry.data());
                stringConnection.expire(entry.key(), 7200L);
            }
            return null;
        });

        log.info("게임 세션 생성 완료 - 로비 코드: {}, 문제 갯수: {}, 참여자 수: {}",
                 code, lobby.getQuestionCount(), participantIdentifiers.size());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("DB 트랜잭션 롤백 감지 - Redis 세션 잔여 데이터 정리. code: {}", code);
                    /*
                     * 통합 정리 스크립트로 base 3종 + 라운드별 6종 키를 원자적으로 삭제한다.
                     * (기존 개별 delete는 ready/playback_lock/correct_times/ended_lock 등을 누락할 수 있었다)
                     */
                    gameSessionCleanupService.deleteNow(code);
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

    /** 라운드별 문제 데이터 캐싱을 파이프라인으로 일괄 전송하기 위한 (키, 해시 필드) 묶음. */
    private record RoundCacheEntry(String key, Map<String, String> data) {
    }
}
