package io.github.ascrew.monomatbe.domain.game.controller;

import io.github.ascrew.monomatbe.domain.game.dto.GameChatMessageDto;
import io.github.ascrew.monomatbe.domain.game.dto.PlaybackErrorReportDto;
import io.github.ascrew.monomatbe.domain.game.dto.ReadyToPlayRequest;
import io.github.ascrew.monomatbe.domain.game.service.GameAnswerService;
import io.github.ascrew.monomatbe.domain.game.service.GameRoundStartService;
import io.github.ascrew.monomatbe.domain.game.service.GameSkipVoteService;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class GameEventController {

    private final GameRoundStartService gameRoundStartService;
    private final GameAnswerService gameAnswerService;
    private final GameSkipVoteService gameSkipVoteService;

    @MessageMapping("/game/{code}/ready-to-play")
    public void readyToPlay(
            @DestinationVariable("code") String code,
            @Payload ReadyToPlayRequest request,
            SimpMessageHeaderAccessor accessor) {
        
        String userIdentifier = extractUserIdentifier(accessor);
        if (!StringUtils.hasText(userIdentifier)) {
            log.warn("GameEventController: 인증 정보 없음 - code: {}", code);
            return;
        }

        if (request == null || request.roundNo() <= 0) {
            log.warn("GameEventController: 잘못된 ready-to-play 요청 - code: {}, request: {}", code, request);
            return;
        }

        gameRoundStartService.processReadyToPlay(code, userIdentifier, request.roundNo());
    }

    @MessageMapping("/game/{code}/chat")
    public void handleGameChat(
            @DestinationVariable("code") String code,
            @Payload @Valid GameChatMessageDto messageDto,
            SimpMessageHeaderAccessor accessor) {

        String userIdentifier = extractUserIdentifier(accessor);
        if (!StringUtils.hasText(userIdentifier)) {
            log.warn("GameEventController: 인증 정보 없음 - code: {}", code);
            return;
        }

        gameAnswerService.processGameChat(code, userIdentifier, messageDto);
    }

    @MessageMapping("/game/{code}/playback-error")
    public void handlePlaybackError(
            @DestinationVariable("code") String code,
            @Payload @Valid PlaybackErrorReportDto request,
            SimpMessageHeaderAccessor accessor) {

        String userIdentifier = extractUserIdentifier(accessor);
        if (!StringUtils.hasText(userIdentifier)) {
            log.warn("GameEventController: 인증 정보 없음 - code: {}", code);
            return;
        }

        if (request == null || request.roundNo() == null || request.roundNo() <= 0) {
            log.warn("GameEventController: 잘못된 playback-error 요청 - code: {}, request: {}", code, request);
            return;
        }

        gameSkipVoteService.reportPlaybackError(code, userIdentifier, request);
    }

    private String extractUserIdentifier(SimpMessageHeaderAccessor accessor) {
        if (accessor == null) {
            return null;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }

        Object value = sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        if (value instanceof String userIdentifier && StringUtils.hasText(userIdentifier)) {
            return userIdentifier;
        }

        return null;
    }
}
