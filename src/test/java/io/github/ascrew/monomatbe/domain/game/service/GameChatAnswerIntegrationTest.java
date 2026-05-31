package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfile;
import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfileResolver;
import io.github.ascrew.monomatbe.domain.game.dto.GameChatMessageDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundCorrectResponse;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class GameChatAnswerIntegrationTest {

    private static final String LOBBY_CODE = "GAME12";
    private static final String USER_ID = "test-user-id";
    private static final String NICKNAME = "테스트유저";

    @Autowired
    private GameAnswerService gameAnswerService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private LobbyRepository lobbyRepository;

    @MockitoBean
    private LobbyPlayerNicknameResolver nicknameResolver;

    @MockitoBean
    private ChatSenderProfileResolver chatSenderProfileResolver;

    @BeforeEach
    void setUp() {
        // Mock 기본 셋팅
        when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(true);
        when(lobbyRepository.isParticipant(LOBBY_CODE, USER_ID)).thenReturn(true);
        
        Map<String, String> nicknameMap = new HashMap<>();
        nicknameMap.put(USER_ID, NICKNAME);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(nicknameMap);
        when(nicknameResolver.fallbackNickname(USER_ID)).thenReturn(NICKNAME);

        ChatSenderProfile profile = ChatSenderProfile.builder()
                .userIdentifier(USER_ID)
                .userId(1L)
                .nickname(NICKNAME)
                .build();
        when(chatSenderProfileResolver.resolve(USER_ID)).thenReturn(profile);
    }

    @AfterEach
    void tearDown() {
        // 임시 Redis Key 데이터 정리
        redisTemplate.delete(RedisKeys.gameSessionKey(LOBBY_CODE));
        redisTemplate.delete(RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundCorrectTimesKey(LOBBY_CODE, 1));
    }

    private void givenGameSession(String status, int currentRoundNo, int timeLimit, Long playbackStartedAt) {
        String sessionKey = RedisKeys.gameSessionKey(LOBBY_CODE);
        redisTemplate.opsForHash().put(sessionKey, "status", status);
        redisTemplate.opsForHash().put(sessionKey, "current_round_no", String.valueOf(currentRoundNo));
        redisTemplate.opsForHash().put(sessionKey, "time_limit_seconds", String.valueOf(timeLimit));
        if (playbackStartedAt != null) {
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.gameSessionRoundPlaybackStartedAtField(currentRoundNo), String.valueOf(playbackStartedAt));
        }

        // 정답 데이터 설정 (answers 및 normalized_answers)
        String roundDataKey = RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, currentRoundNo);
        redisTemplate.opsForHash().put(roundDataKey, "answers", "[\"dynamite\",\"다이너마이트\"]");
        redisTemplate.opsForHash().put(roundDataKey, "normalized_answers", "[\"dynamite\",\"다이너마이트\"]");
    }

    @Test
    @DisplayName("오답을 입력하면 일반 대화 채널로 브로드캐스트된다")
    void wrongAnswerBroadcastsAsNormalChat() {
        // given
        givenGameSession("PLAYING", 1, 30, System.currentTimeMillis());
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "hello bts");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(broadcasted.getSender()).isEqualTo(NICKNAME);
        assertThat(broadcasted.getContent()).isEqualTo("hello bts");
        
        // 정답자 추가되지 않음
        Boolean isCorrect = redisTemplate.opsForSet().isMember(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        assertThat(isCorrect).isFalse();
    }

    @Test
    @DisplayName("정답을 입력하면 원래 텍스트는 차단되고, 맞춘 사람 추가 및 시스템 축하 메시지가 전파된다")
    void correctAnswerBlocksTextAndBroadcastsSystemAlert() {
        // given
        givenGameSession("PLAYING", 1, 30, System.currentTimeMillis());
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "다이너마이트 [MV]"); // 메타데이터 정규화 포함

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        // 1. 시스템 정답 공지 브로드캐스트 발생 검증
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.SYSTEM);
        assertThat(broadcasted.getContent()).contains(NICKNAME + "님이 정답을 맞췄습니다!");

        // 2. 정답자 본인에게 개인 축하 메시지 수신 검증
        verify(messagingTemplate).convertAndSendToUser(eq(USER_ID), eq("/queue/game/answers"), any(RoundCorrectResponse.class));

        // 3. Redis Set에 정답자로 등록 검증
        Boolean isCorrect = redisTemplate.opsForSet().isMember(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        assertThat(isCorrect).isTrue();
    }

    @Test
    @DisplayName("이미 정답을 맞춘 사용자가 대화할 경우 정답 유무 검사 없이 일반 대화로 송출된다")
    void correctPlayerNormalChatBroadcastsDirectly() {
        // given
        givenGameSession("PLAYING", 1, 30, System.currentTimeMillis());
        // 정답자로 등록 처리
        redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "노래 진짜 좋네요!");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(broadcasted.getContent()).isEqualTo("노래 진짜 좋네요!");
    }

    @Test
    @DisplayName("이미 정답을 맞춘 사용자가 정답 키워드를 한번 더 도배(스포일러 트롤링)하려 하면 *** 로 마스킹 처리된다")
    void correctPlayerSpoilerFilteredWithMasking() {
        // given
        givenGameSession("PLAYING", 1, 30, System.currentTimeMillis());
        // 정답자로 등록 처리
        redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "다이너마이트");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(broadcasted.getContent()).isEqualTo("***"); // 정답 문자 스포일러 차단
    }

    @Test
    @DisplayName("이미 정답을 맞춘 사용자의 메시지에 정답 키워드가 포함(부분 스포일러)되어 있으면 *** 로 마스킹 처리된다")
    void correctPlayerSubstringSpoilerFilteredWithMasking() {
        // given
        givenGameSession("PLAYING", 1, 30, System.currentTimeMillis());
        // 정답자로 등록 처리
        redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "정답은 다이너마이트 입니다!");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(broadcasted.getContent()).isEqualTo("***"); // 포함된 정답 스포일러 차단
    }

    @Test
    @DisplayName("제한 시간이 지나서 들어온 정답 제출은 정답일 경우 ***로 마스킹 처리되어 일반 채팅으로 송출된다")
    void timeoutAnswerWithCorrectWordIsMasked() {
        // given (현재 시간 대비 32초 전 재생 시작하여 제한시간 30초 만료됨)
        long startedAt = System.currentTimeMillis() - (32 * 1000L);
        givenGameSession("PLAYING", 1, 30, startedAt);
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "다이너마이트");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(broadcasted.getContent()).isEqualTo("***"); // 만료된 정답 제출은 스포일러 방지를 위해 마스킹 처리됨

        Boolean isCorrect = redisTemplate.opsForSet().isMember(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        assertThat(isCorrect).isFalse(); // 정답 처리는 되지 않음
    }

    @Test
    @DisplayName("제한 시간이 지나서 들어온 일반 채팅(오답)은 마스킹 없이 그대로 송출된다")
    void timeoutAnswerWithWrongWordIsBroadcastedAsIs() {
        // given
        long startedAt = System.currentTimeMillis() - (32 * 1000L);
        givenGameSession("PLAYING", 1, 30, startedAt);
        GameChatMessageDto messageDto = new GameChatMessageDto(1, "그냥 일반 채팅");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID, messageDto);

        // then
        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto broadcasted = chatCaptor.getValue();
        assertThat(broadcasted.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(broadcasted.getContent()).isEqualTo("그냥 일반 채팅");

        Boolean isCorrect = redisTemplate.opsForSet().isMember(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1), USER_ID);
        assertThat(isCorrect).isFalse();
    }

    @Test
    @DisplayName("Java RedisKeys 필드명 포맷과 Lua 스크립트 내부 하드코딩 필드명이 일치하는지 검증한다")
    void validateJavaAndLuaRedisFieldContract() throws java.io.IOException {
        // Given
        String expectedFieldPrefix = "playback_started_at:";
        int testRoundNo = 5;
        String javaGeneratedField = RedisKeys.gameSessionRoundPlaybackStartedAtField(testRoundNo);
        assertThat(javaGeneratedField).isEqualTo(expectedFieldPrefix + testRoundNo);

        // Lua 파일 내에서 해당 패턴이 존재하는지 검증
        org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("scripts/submit_game_answer.lua");
        String luaContent = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        
        assertThat(luaContent).contains("'playback_started_at:'");
    }
}
