/*
 * 로비 조회 관련 비즈니스 로직을 담당하는 서비스.
 *
 * [LobbyController에서 Repository 직접 참조를 제거한 이유]
 * 컨트롤러가 Repository를 직접 참조하면 레이어 경계가 무너집니다.
 * 서비스 레이어를 경유함으로써 향후 캐싱, 트랜잭션, 추가 비즈니스 로직을
 * 적용할 수 있는 확장 지점을 확보합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyService {

    private static final String ERROR_USER_NOT_FOUND = "사용자를 찾을 수 없습니다.";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final UserRepository userRepository;

    /**
     * 로비를 생성한다.
     *
     * [처리 순서]
     * 1. JWT에서 추출한 userId로 User 엔티티 조회
     * 2. SETNX로 초대 코드 선점 및 Redis에 로비 데이터 저장
     * 3. DB에 GAME_LOBBY 스냅샷 Insert
     *
     * [트랜잭션 설계]
     * Redis 저장을 DB Insert보다 먼저 수행합니다.
     * Redis 저장 실패 시 DB Insert가 실행되지 않아 고아 데이터가 발생하지 않습니다.
     * DB Insert 실패 시 Redis 데이터는 남을 수 있으나,
     * 로비 폭파(leave_lobby.lua) 시 Redis 키가 정리되므로 허용 가능한 수준입니다.
     *
     * @param request   로비 생성 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 생성된 로비 정보 응답 DTO
     */
    @Transactional
    public CreateLobbyResponse createLobby(CreateLobbyRequest request, CustomPrincipal principal) {
        User host = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_USER_NOT_FOUND));

        String inviteCode = lobbyRepository.saveToRedis(request, principal.userIdentifier());

        GameLobby gameLobby = gameLobbyJpaRepository.save(GameLobby.builder()
                .host(host)
                .inviteCode(inviteCode)
                .title(request.title())
                .maxPlayers(request.maxPlayers())
                .roundCount(request.roundCount())
                .timeLimitSeconds(request.timeLimitSeconds())
                .isPrivate(request.isPrivate())
                .build());

        log.info("로비 생성 완료 - 코드: {}, 방장: {}", inviteCode, principal.userIdentifier());

        return CreateLobbyResponse.builder()
                .lobbyId(gameLobby.getId())
                .inviteCode(inviteCode)
                .title(gameLobby.getTitle())
                .maxPlayers(gameLobby.getMaxPlayers())
                .isPrivate(gameLobby.getIsPrivate())
                .status(gameLobby.getStatus().name())
                .build();
    }

    /**
     * 공개 로비 목록을 조회합니다.
     * Redis에서 직접 필터링하여 공개(isPrivate=false) 로비만 반환합니다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyRepository.getPublicLobbies();
    }
}