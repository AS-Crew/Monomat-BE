package io.github.ascrew.monomatbe.domain.game.controller;

import io.github.ascrew.monomatbe.domain.game.dto.ReadyToPlayRequest;
import io.github.ascrew.monomatbe.domain.game.service.GameRoundStartService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class GameEventController {

    private final GameRoundStartService gameRoundStartService;

    @MessageMapping("/game/{code}/ready-to-play")
    public void readyToPlay(
            @DestinationVariable("code") String code,
            @Payload ReadyToPlayRequest request,
            CustomPrincipal principal) {
        
        if (principal == null || principal.userIdentifier() == null) {
            log.warn("GameEventController: 인증 정보 없음 - code: {}", code);
            return;
        }

        if (request == null || request.roundNo() <= 0) {
            log.warn("GameEventController: 잘못된 ready-to-play 요청 - code: {}, request: {}", code, request);
            return;
        }

        gameRoundStartService.processReadyToPlay(code, principal.userIdentifier(), request.roundNo());
    }
}
