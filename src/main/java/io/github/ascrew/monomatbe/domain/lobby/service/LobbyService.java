/*
 * 로비 조회 관련 비즈니스 로직을 담당하는 서비스.
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

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_USER_NOT_FOUND =
            "사용자를 찾을 수 없습니다.";
    private static final String ERROR_CREATE_LOBBY_FAILED =
            "로비 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_INVALID_PRINCIPAL =
            "로비 생성 요청 거부 - principal 또는 userId가 null. userIdentifier: {}";
    private static final String LOG_DB_SAVE_FAILED =
            "DB 로비 저장 실패 - Redis 보상 삭제 시작. 코드: {}";
    private static final String LOG_COMPENSATION_SUCCESS =
            "Redis 보상 삭제 완료 - 코드: {}";
    private static final String LOG_COMPENSATION_FAILED =
            "Redis 보상 삭제 실패 - 코드: {}. 수동 정리 필요. [모니터링 필요]";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final UserRepository userRepository;

    /**
     * 로비를 생성한다.
     *
     * [처리 순서]
     * 1. principal null 및 userId null 검증 → 401 반환
     * 2. JWT에서 추출한 userId로 User 엔티티 조회
     * 3. Lua 스크립트로 초대 코드 선점 및 Redis에 로비 데이터 원자적 저장
     * 4. DB에 GAME_LOBBY 스냅샷 Insert
     * 5. DB 실패 시 Redis 보상 삭제 및 성공/실패 여부를 구분하여 로그 기록
     *
     * @param request   로비 생성 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 생성된 로비 정보 응답 DTO
     */
    @Transactional
    public CreateLobbyResponse createLobby(CreateLobbyRequest request, CustomPrincipal principal) {

        // principal 및 userId null 방어
        // JwtAuthenticationFilter를 통과했더라도 토큰 파싱 이상으로
        // userId가 null인 채로 진입할 수 있으므로 서비스 레이어에서 재검증한다.
        if (principal == null || principal.userId() == null) {
            log.warn(LOG_INVALID_PRINCIPAL,
                    principal != null ? principal.userIdentifier() : "null");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        User host = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_USER_NOT_FOUND));

        String inviteCode = lobbyRepository.saveToRedis(request, principal.userIdentifier());

        try {
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

        } catch (Exception e) {
            // 보상 삭제 성공/실패를 구분하여 모니터링 가능한 로그 기록
            log.error(LOG_DB_SAVE_FAILED, inviteCode, e);

            boolean compensationSuccess = lobbyRepository.deleteFromRedis(inviteCode);

            if (compensationSuccess) {
                // 보상 삭제 성공: 데이터 정합성 유지됨
                log.info(LOG_COMPENSATION_SUCCESS, inviteCode);
            } else {
                // 보상 삭제 실패: Redis에 좀비 로비 데이터가 남아있을 수 있음
                log.error(LOG_COMPENSATION_FAILED, inviteCode);
            }

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, ERROR_CREATE_LOBBY_FAILED);
        }
    }

    /**
     * 공개 로비 목록을 조회한다.
     * Redis에서 직접 필터링하여 공개(isPrivate=false) 로비만 반환
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyRepository.getPublicLobbies();
    }
}