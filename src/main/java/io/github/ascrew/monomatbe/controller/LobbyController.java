package io.github.ascrew.monomatbe.controller;

import io.github.ascrew.monomatbe.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.dto.request.LobbyCreateRequestDto;
import io.github.ascrew.monomatbe.dto.response.LobbyCreateResponseDto;
import io.github.ascrew.monomatbe.repository.LobbyRepository;
import io.github.ascrew.monomatbe.service.LobbyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
    private final LobbyService lobbyService;

    /**
     * 고속 로비 리스트 조회 API
     * 프론트엔드에서 메인 로비 목록 화면 진입 시 호출
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

    // 로비 생성 API
    // [인가]
    // 현재 인증 체계가 미완성이므로 hostUserId를 임시 하드코딩 처리
    // TODO : 인증 구현 후 Security Context에서 hostUserId를 추출하도록 교체 필요

    // [응답 코드]
    // 리소스 생성 성공은 REST 관례상 200 OK가 아닌 201 Created
    // @param requestDto 클라이언트가 전달한 로비 생성 요청 데이터
    // @return 201 Created + 생성된 로비 정보 및 딥링크
    @PostMapping
    public ResponseEntity<LobbyCreateResponseDto> createLobby(
            @Valid @RequestBody LobbyCreateRequestDto requestDto) {

        log.info ("요청 수신 : 로비 생성 [POST /api/lobbies] - title : {}, maxPlayers : {}, isPrivate: {}",
                requestDto.getTitle(), requestDto.getMaxPlayers(), requestDto.getIsPrivate());

        // TODO : 인증 구현 후 아래 하드코딩을 Security Context 추출로 교체
        // 게스트/정식 회원 모두 로비 생성 가능
        // 게스트의 경우 UUID 기반으로 발급된 users.id를 사용
        Long hostUserId = 1L;

        LobbyCreateResponseDto responseDto = lobbyService.createLobby(requestDto, hostUserId);

        log.info("로비 생성 완료 - lobbyId : {}, inviteCode : {}", responseDto.getLobbyId(), responseDto.getInviteCode());

        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);

    }
}