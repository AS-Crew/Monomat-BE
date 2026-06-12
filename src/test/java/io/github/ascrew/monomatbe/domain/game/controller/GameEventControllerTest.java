package io.github.ascrew.monomatbe.domain.game.controller;

import io.github.ascrew.monomatbe.domain.game.dto.GameChatMessageDto;
import io.github.ascrew.monomatbe.domain.game.dto.PlaybackErrorReportDto;
import io.github.ascrew.monomatbe.domain.game.dto.ReadyToPlayRequest;
import io.github.ascrew.monomatbe.domain.game.service.GameAnswerService;
import io.github.ascrew.monomatbe.domain.game.service.GameRoundStartService;
import io.github.ascrew.monomatbe.domain.game.service.GameSkipVoteService;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GameEventControllerTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "user-identifier";

    @Mock
    private GameRoundStartService gameRoundStartService;

    @Mock
    private GameAnswerService gameAnswerService;

    @Mock
    private GameSkipVoteService gameSkipVoteService;

    @InjectMocks
    private GameEventController gameEventController;

    @Test
    @DisplayName("ready-to-play 요청은 STOMP 세션의 사용자 식별자와 함께 서비스로 위임한다")
    void readyToPlayDelegatesToService() {
        // given
        ReadyToPlayRequest request = new ReadyToPlayRequest(1);

        // when
        gameEventController.readyToPlay(LOBBY_CODE, request, accessorWithUserIdentifier());

        // then
        verify(gameRoundStartService).processReadyToPlay(LOBBY_CODE, USER_IDENTIFIER, 1);
    }

    @Test
    @DisplayName("게임 채팅 요청은 STOMP 세션의 사용자 식별자와 함께 서비스로 위임한다")
    void gameChatDelegatesToService() {
        // given
        GameChatMessageDto request = new GameChatMessageDto(1, "/k");

        // when
        gameEventController.handleGameChat(LOBBY_CODE, request, accessorWithUserIdentifier());

        // then
        verify(gameAnswerService).processGameChat(LOBBY_CODE, USER_IDENTIFIER, request);
    }

    @Test
    @DisplayName("playback-error 요청은 인증 사용자의 식별자와 함께 서비스로 위임한다")
    void playbackErrorDelegatesToService() {
        // given
        PlaybackErrorReportDto request = new PlaybackErrorReportDto(1, "ERROR", "message");

        // when
        gameEventController.handlePlaybackError(LOBBY_CODE, request, accessorWithUserIdentifier());

        // then
        verify(gameSkipVoteService).reportPlaybackError(LOBBY_CODE, USER_IDENTIFIER, request);
    }

    @Test
    @DisplayName("playback-error 요청에 인증 정보가 없으면 서비스로 위임하지 않는다")
    void playbackErrorWithoutPrincipalIsIgnored() {
        // given
        PlaybackErrorReportDto request = new PlaybackErrorReportDto(1, "ERROR", "message");

        // when
        gameEventController.handlePlaybackError(LOBBY_CODE, request, null);

        // then
        verify(gameSkipVoteService, never()).reportPlaybackError(LOBBY_CODE, USER_IDENTIFIER, request);
    }

    @Test
    @DisplayName("playback-error 요청의 roundNo가 유효하지 않으면 서비스로 위임하지 않는다")
    void playbackErrorWithInvalidRoundNoIsIgnored() {
        // given
        PlaybackErrorReportDto request = new PlaybackErrorReportDto(0, "ERROR", "message");

        // when
        gameEventController.handlePlaybackError(LOBBY_CODE, request, accessorWithUserIdentifier());

        // then
        verify(gameSkipVoteService, never()).reportPlaybackError(LOBBY_CODE, USER_IDENTIFIER, request);
    }

    private SimpMessageHeaderAccessor accessorWithUserIdentifier() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketHeaders.USER_IDENTIFIER, USER_IDENTIFIER);
        accessor.setSessionAttributes(attributes);
        return accessor;
    }
}
