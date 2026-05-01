/*
 * 로비 관련 HTTP REST API를 처리하는 컨트롤러.
 *
 * [책임]
 * 클라이언트의 명시적인 데이터 조회 요청을 수신하고
 * LobbyService에 위임한 뒤 응답을 반환합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/lobbies")
@RequiredArgsConstructor
public class LobbyController {

    private final LobbyService lobbyService;

    /**
     * 공개 로비 목록 조회 API.
     * Redis에서 직접 필터링하여 공개 로비만 반환합니다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    @GetMapping
    public ResponseEntity<List<LobbyRedisDto>> getPublicLobbies() {
        log.info("요청 수신: 공개 로비 목록 조회 [GET /api/lobbies]");

        List<LobbyRedisDto> publicLobbies = lobbyService.getPublicLobbies();

        log.info("조회 완료: 공개 로비 {}개 반환", publicLobbies.size());

        return ResponseEntity.ok(publicLobbies);
    }
}