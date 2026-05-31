package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfile;
import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfileResolver;
import io.github.ascrew.monomatbe.domain.game.dto.GameChatMessageDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundCorrectResponse;
import io.github.ascrew.monomatbe.domain.game.support.FuzzyMatcher;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.domain.map.support.AnswerNormalizer;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 인게임 채팅 인입 시 정답을 판별하고 알림 및 일반 대화 브로드캐스트의 흐름을 처리하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameAnswerService {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final LobbyRepository lobbyRepository;
    private final LobbyPlayerNicknameResolver lobbyPlayerNicknameResolver;
    private final ChatSenderProfileResolver chatSenderProfileResolver;
    private final JsonMapper jsonMapper;

    /**
     * 인게임 전용 채팅 인입 시 정답 여부를 판별하여 처리합니다.
     *
     * @param code 로비 초대 코드
     * @param userIdentifier 발신자 식별자
     * @param messageDto 인게임 채팅 DTO
     */
    public void processGameChat(String code, String userIdentifier, GameChatMessageDto messageDto) {
        // 1. 기본 검증
        if (!lobbyRepository.existsByCode(code)) {
            log.warn("processGameChat: 존재하지 않는 로비 - code: {}", code);
            return;
        }
        if (!lobbyRepository.isParticipant(code, userIdentifier)) {
            log.warn("processGameChat: 로비 참여자가 아님 - code: {}, user: {}", code, userIdentifier);
            return;
        }

        String sessionKey = RedisKeys.gameSessionKey(code);
        List<Object> fields = List.of(
                "status",
                "current_round_no",
                "time_limit_seconds",
                RedisKeys.gameSessionRoundPlaybackStartedAtField(messageDto.roundNo())
        );
        List<Object> values = redisTemplate.opsForHash().multiGet(sessionKey, fields);
        if (values == null || values.get(0) == null) {
            log.warn("processGameChat: 활성화된 게임 세션이 없음 - code: {}", code);
            return;
        }

        String status = (String) values.get(0);
        if (!"PLAYING".equals(status)) {
            log.warn("processGameChat: 게임 진행 중이 아님 (status: {}) - code: {}", status, code);
            return;
        }

        String currentRoundNoStr = (String) values.get(1);
        int currentRoundNo = currentRoundNoStr != null ? Integer.parseInt(currentRoundNoStr) : 1;
        if (messageDto.roundNo() != currentRoundNo) {
            log.warn("processGameChat: 라운드 번호 불일치 - code: {}, expected: {}, actual: {}", 
                     code, currentRoundNo, messageDto.roundNo());
            return;
        }

        // 2. 시간 초과 검증 (지연 완충 시간 1.5초 고려)
        String playbackStartedAtStr = (String) values.get(3);
        String timeLimitStr = (String) values.get(2);
        int timeLimitSeconds = timeLimitStr != null ? Integer.parseInt(timeLimitStr) : 30;

        if (playbackStartedAtStr != null) {
            long playbackStartedAt = Long.parseLong(playbackStartedAtStr);
            long limitTimeMillis = playbackStartedAt + (timeLimitSeconds * 1000L) + 1500L;
            if (System.currentTimeMillis() > limitTimeMillis) {
                log.info("processGameChat: 제출 시간 초과 - code: {}, user: {}", code, userIdentifier);
                // 만료된 제출은 일반 채팅 브로드캐스트로 무조건 내보냄
                broadcastChatMessage(code, userIdentifier, messageDto.content());
                return;
            }
        }

        // 3. 정답자 데이터 로드
        String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(code, currentRoundNo);
        Boolean isAlreadyCorrect = redisTemplate.opsForSet().isMember(correctPlayersKey, userIdentifier);

        // 4. 캐싱된 문제 정답 로드
        String roundDataKey = RedisKeys.gameSessionRoundDataKey(code, currentRoundNo);
        String normalizedAnswersJson = (String) redisTemplate.opsForHash().get(roundDataKey, "normalized_answers");
        List<String> normalizedAnswers = Collections.emptyList();
        if (normalizedAnswersJson != null) {
            try {
                normalizedAnswers = jsonMapper.readValue(normalizedAnswersJson, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.error("processGameChat: 정답 캐시 역직렬화 실패 - key: {}", roundDataKey, e);
            }
        }

        // 5. 정답자 대화 처리 (스포일러 트롤링 필터 적용)
        if (Boolean.TRUE.equals(isAlreadyCorrect)) {
            String content = messageDto.content();
            String normalizedAnswer = AnswerNormalizer.normalize(content);
            if (!normalizedAnswer.isEmpty()) {
                // 정답을 맞춘 유저의 채팅 중 정답과 100% 일치하거나 오타 매칭이 되는 부분은 마스킹 처리하여 스포일러 방지
                for (String normalizedTarget : normalizedAnswers) {
                    if (normalizedAnswer.contains(normalizedTarget) || FuzzyMatcher.isMatch(normalizedAnswer, normalizedTarget)) {
                        content = "***";
                        break;
                    }
                }
            }
            broadcastChatMessage(code, userIdentifier, content);
            return;
        }

        // 6. 미정답자의 정답 여부 판단
        boolean isCorrect = false;
        boolean isFuzzy = false;

        String normalizedUserAnswer = AnswerNormalizer.normalize(messageDto.content());
        if (!normalizedUserAnswer.isEmpty()) {
            for (String normalizedTarget : normalizedAnswers) {
                if (normalizedUserAnswer.equals(normalizedTarget)) {
                    isCorrect = true;
                    isFuzzy = false;
                    break;
                } else if (FuzzyMatcher.isMatch(normalizedUserAnswer, normalizedTarget)) {
                    isCorrect = true;
                    isFuzzy = true;
                    // Fuzzy 매칭이 되어도 계속 더 가까운 매칭을 찾을 수 있으므로 루프를 유지하되 우선 true 처리
                }
            }
        }

        // 7. 결과 분기 처리
        if (isCorrect) {
            // 정답자 추가 (SADD의 리턴값이 1일 때만 최초 정답 달성으로 취급)
            Long addedCount = redisTemplate.opsForSet().add(correctPlayersKey, userIdentifier);
            if (addedCount != null && addedCount == 1) {
                String nickname = getNickname(userIdentifier);
                log.info("processGameChat: 정답 달성! - code: {}, user: {} ({}), fuzzy: {}", 
                         code, userIdentifier, nickname, isFuzzy);

                // 정답 달성 시스템 공지 브로드캐스트
                broadcastSystemMessage(code, nickname + "님이 정답을 맞췄습니다!");

                // 정답 달성자 본인에게 개인 축하 응답 전송
                sendDirectCorrectResponse(userIdentifier, currentRoundNo, isFuzzy);
            }
        } else {
            // 오답인 경우 일반 채팅으로 전송
            broadcastChatMessage(code, userIdentifier, messageDto.content());
        }
    }

    private void broadcastChatMessage(String code, String userIdentifier, String content) {
        String nickname = getNickname(userIdentifier);
        ChatMessageDto chatMessage = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(code)
                .sender(nickname)
                .content(content)
                .timestamp(LocalDateTime.now().toString())
                .build();
        messagingTemplate.convertAndSend("/topic/game/" + code + "/chat", chatMessage);
    }

    private void broadcastSystemMessage(String code, String content) {
        ChatMessageDto systemMessage = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.SYSTEM)
                .roomId(code)
                .sender("SYSTEM")
                .content(content)
                .timestamp(LocalDateTime.now().toString())
                .build();
        messagingTemplate.convertAndSend("/topic/game/" + code + "/chat", systemMessage);
    }

    private void sendDirectCorrectResponse(String userIdentifier, int roundNo, boolean isFuzzy) {
        RoundCorrectResponse response = RoundCorrectResponse.builder()
                .type("ROUND_CORRECT")
                .roundNo(roundNo)
                .isFuzzy(isFuzzy)
                .message(isFuzzy ? "오타 허용 정답입니다!" : "완벽한 정답입니다!")
                .build();
        messagingTemplate.convertAndSendToUser(userIdentifier, "/queue/game/answers", response);
    }

    private String getNickname(String userIdentifier) {
        try {
            ChatSenderProfile profile = chatSenderProfileResolver.resolve(userIdentifier);
            if (profile != null && profile.getNickname() != null) {
                return profile.getNickname();
            }
        } catch (Exception e) {
            log.warn("getNickname: 캐시 프로필 조회 실패. userIdentifier: {}", userIdentifier, e);
        }
        return lobbyPlayerNicknameResolver.fallbackNickname(userIdentifier);
    }
}
