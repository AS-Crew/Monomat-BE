package io.github.ascrew.monomatbe.controller;

import io.github.ascrew.monomatbe.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 로비 관련 HTTP REST API를 처리하는 컨트롤러
 * 실시간 상태 알림(WebSocket)과는 별개로, 클라이언트의 명시적인 데이터 '조회' 요청을 담당
 */
@Slf4j // 롬복(Lombok)이 제공하는 로깅 어노테이션. log 객체를 자동으로 생성해 줍니다.
@RestController
@RequestMapping("/api/lobbies")
@RequiredArgsConstructor
public class LobbyController {

    private final LobbyRepository lobbyRepository;

    /**
     * 고속 로비 리스트 조회 API
     * 프론트엔드(React)에서 메인 로비 목록 화면에 진입하거나 '새로고침' 버튼을 눌렀을 때 호출된다.
     */
    @GetMapping
    public ResponseEntity<List<LobbyRedisDto>> getPublicLobbies() {
        // API 요청이 서버에 정상적으로 도달했는지 확인하기 위한 로그
        log.info("요청 수신: 고속 로비 목록 조회 [GET /api/lobbies]");

        // Repository를 통해 Redis에서 데이터를 직접 필터링하여 가져온다.
        List<LobbyRedisDto> publicLobbies = lobbyRepository.getPublicLobbies();

        log.info("조회 완료: 현재 활성화된 공개 방 {} 개 반환", publicLobbies.size());

        // HTTP 상태 코드 200(OK)와 함께 데이터를 프론트엔드에 응답
        return ResponseEntity.ok(publicLobbies);
    }
}