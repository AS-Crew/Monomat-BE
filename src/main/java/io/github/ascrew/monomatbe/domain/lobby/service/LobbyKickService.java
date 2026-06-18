/*
 * 로비 강퇴 유스케이스 전용 서비스
 *
 * [책임]
 * - 강퇴 요청 입력값 검증
 * - kick_lobby.lua 실행 위임
 * - Lua 결과를 HTTP/STOMP 처리 가능한 예외로 변환
 * - 강퇴 성공 후 ws:connection 키 정리
 * - KICK 메시지 및 로비 refresh 알림 발행 위임
 *
 * [설계 의도]
 * 강퇴는 단순 알림이 아니라 Redis 참여자 상태를 변경하는 명확한 유스케이스다.
 * 따라서 STOMP 브로드캐스트 서비스가 아니라 별도 도메인 서비스에서 처리한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.KickLobbyPlayerRequest;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyKickService {

    private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

    private static final Pattern USER_IDENTIFIER_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final String ERROR_INVALID_LOBBY_CODE = "유효하지 않은 로비 코드입니다.";
    private static final String ERROR_INVALID_PRINCIPAL = "인증 정보가 유효하지 않습니다.";
    private static final String ERROR_INVALID_KICK_TARGET = "강퇴 대상 식별자가 유효하지 않습니다.";
    private static final String ERROR_INVALID_KICK_TARGET_FORMAT = "강퇴 대상 식별자 형식이 올바르지 않습니다.";
    private static final String ERROR_LOBBY_NOT_FOUND = "존재하지 않는 로비입니다.";
    private static final String ERROR_HOST_NOT_FOUND = "로비 방장 정보가 유효하지 않습니다.";
    private static final String ERROR_FORBIDDEN = "방장만 유저를 강퇴할 수 있습니다.";
    private static final String ERROR_CANNOT_KICK_SELF = "자기 자신은 강퇴할 수 없습니다.";
    private static final String ERROR_TARGET_NOT_PARTICIPANT = "강퇴 대상이 로비 참여자가 아닙니다.";
    private static final String ERROR_KICK_FAILED = "강퇴 처리에 실패했습니다.";

    private final LobbyRepository lobbyRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;

    /**
     * 방장의 로비 유저 강퇴 요청을 처리한다.
     *
     * [처리 흐름]
     * 1. 로비 코드, 요청자, 강퇴 대상 식별자 검증
     * 2. kick_lobby.lua 실행
     * 3. Lua 결과를 도메인 결과 타입으로 판단
     * 4. 성공 시 강퇴 대상 ws:connection 키 정리
     * 5. KICK 메시지 발행
     * 6. 로비 정보 refresh 발행
     *
     * [주의]
     * 실제 participants/order/kicked 상태 변경은 Lua에서 원자적으로 처리한다.
     */
    public void kickLobbyPlayer(
            String code,
            KickLobbyPlayerRequest request,
            String requesterIdentifier
    ) {
        validateKickRequest(code, request, requesterIdentifier);

        String targetUserIdentifier = request.targetUserIdentifier().trim();

        KickLobbyResult result = lobbyRepository.executeKickLobbyProcess(
                code,
                requesterIdentifier,
                targetUserIdentifier
        );

        switch (result) {
            case KickLobbyResult.Kicked kicked -> handleKickSuccess(kicked);

            case KickLobbyResult.LobbyNotFound ignored -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );

            case KickLobbyResult.HostNotFound ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_HOST_NOT_FOUND
            );

            case KickLobbyResult.Forbidden ignored -> throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_FORBIDDEN
            );

            case KickLobbyResult.CannotKickSelf ignored -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_CANNOT_KICK_SELF
            );

            case KickLobbyResult.TargetNotParticipant ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_TARGET_NOT_PARTICIPANT
            );

            case KickLobbyResult.Error error -> {
                log.error("강퇴 처리 실패 - reason: {}", error.reason());
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ERROR_KICK_FAILED
                );
            }
        }
    }

    private void validateKickRequest(
            String code,
            KickLobbyPlayerRequest request,
            String requesterIdentifier
    ) {
        if (!StringUtils.hasText(code) || !LOBBY_CODE_PATTERN.matcher(code).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_LOBBY_CODE
            );
        }

        if (!StringUtils.hasText(requesterIdentifier)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    ERROR_INVALID_PRINCIPAL
            );
        }

        if (request == null || !StringUtils.hasText(request.targetUserIdentifier())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_KICK_TARGET
            );
        }

        if (!USER_IDENTIFIER_PATTERN.matcher(request.targetUserIdentifier().trim()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_KICK_TARGET_FORMAT
            );
        }
    }

    private void handleKickSuccess(KickLobbyResult.Kicked result) {
        String lobbyCode = result.lobbyCode();
        String targetUserIdentifier = result.targetUserIdentifier();

        deleteTargetWsConnection(result.targetWsSessionId());

        boolean kickMessagePublished = lobbyRealtimeNotifier.notifyKickMessage(
                lobbyCode,
                targetUserIdentifier
        );

        if (!kickMessagePublished) {
            log.error(
                    "KICK 메시지 전송 실패 - Redis 강퇴 상태는 반영되었으나 클라이언트 알림이 누락될 수 있음. "
                            + "lobbyCode: {}, targetUserIdentifier: {}",
                    lobbyCode,
                    targetUserIdentifier
            );
        }

        lobbyRealtimeNotifier.notifyLobbyInfoRefresh(lobbyCode);
        // 목록 화면 구독자에게도 current_players 변동을 알린다. (#203)
        lobbyRealtimeNotifier.notifyLobbyListRefresh();

        log.info(
                "로비 유저 강퇴 완료 - lobbyCode: {}, targetUserIdentifier: {}, targetWsSessionId: {}",
                lobbyCode,
                targetUserIdentifier,
                result.targetWsSessionId()
        );
    }

    private void deleteTargetWsConnection(String targetWsSessionId) {
        if (!StringUtils.hasText(targetWsSessionId)) {
            return;
        }

        try {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(targetWsSessionId));
        } catch (Exception e) {
            log.warn(
                    "강퇴 대상 ws:connection 키 삭제 실패 - targetWsSessionId: {}",
                    targetWsSessionId,
                    e
            );
        }
    }
}