package io.github.ascrew.monomatbe.domain.game.controller;

import io.github.ascrew.monomatbe.domain.game.dto.CurrentRoundStatusResponse;
import io.github.ascrew.monomatbe.domain.game.service.GameSessionQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameSessionController {

    private final GameSessionQueryService gameSessionQueryService;

    @GetMapping("/{code}/round/current")
    public ResponseEntity<CurrentRoundStatusResponse> getCurrentRoundStatus(
            @PathVariable("code") String code,
            CustomPrincipal principal) {
        CurrentRoundStatusResponse response = gameSessionQueryService.getCurrentRoundStatus(code, principal.userIdentifier());
        return ResponseEntity.ok(response);
    }
}
