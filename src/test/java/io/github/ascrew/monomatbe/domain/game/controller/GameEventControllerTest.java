package io.github.ascrew.monomatbe.domain.game.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.game.dto.PlaybackErrorReportDto;
import io.github.ascrew.monomatbe.domain.game.service.GameAnswerService;
import io.github.ascrew.monomatbe.domain.game.service.GameRoundStartService;
import io.github.ascrew.monomatbe.domain.game.service.GameSkipVoteService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @DisplayName("playback-error 요청은 인증 사용자의 식별자와 함께 서비스로 위임한다")
    void playbackErrorDelegatesToService() {
        // given
        CustomPrincipal principal = new CustomPrincipal(1L, USER_IDENTIFIER, UserType.REGISTERED);
        PlaybackErrorReportDto request = new PlaybackErrorReportDto(1, "ERROR", "message");

        // when
        gameEventController.handlePlaybackError(LOBBY_CODE, request, principal);

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
        CustomPrincipal principal = new CustomPrincipal(1L, USER_IDENTIFIER, UserType.REGISTERED);
        PlaybackErrorReportDto request = new PlaybackErrorReportDto(0, "ERROR", "message");

        // when
        gameEventController.handlePlaybackError(LOBBY_CODE, request, principal);

        // then
        verify(gameSkipVoteService, never()).reportPlaybackError(LOBBY_CODE, USER_IDENTIFIER, request);
    }
}
