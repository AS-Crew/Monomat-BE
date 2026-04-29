// 로비 생성 서비스
// invite_code 생성 및 중복 체크 6자리 코드를 랜덤으로 생성하고, Redis (1차) -> DB (2차) 순서로 중복 체크
// Redis를 먼저 체크하는 이유는 현재 활성화된 로비는 Redis에 올라와 있으므로 DB까지 가지 않고 빠르게 걸러낼 수 있기 때문

// DB 저장 -> Redis 저장 순서 :
// DB를 먼저 저장하는 이유는 DB 저장이 실패했을 때 Redis에 좀비 데이터가 남는 상황을 방지하기 위함

// 공개 로비면 lobby:public Set에 추가 :
// LobbyRepositoryImpl.getPublicLobbies()가 이 Set을 기준으로 목록을 반환하므로, 생성 시점에 추가

// TODO : DB 저장 성공 후 Redis 저장이 실패하는 경우, DB에는 로비가 있지만 실시간 목록에는 안 보이는 불일치가 발생할 수 있음. 현재 단계에서는 로그로 감지하는 수준으로 처리하고, 추후 필요 시 보상 트랜잭션 고려

package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.domain.GameLobby;
import io.github.ascrew.monomatbe.dto.request.LobbyCreateRequestDto;
import io.github.ascrew.monomatbe.dto.response.LobbyCreateResponseDto;
import io.github.ascrew.monomatbe.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyService {

    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final LobbyRepository lobbyRepository;      // Redis 담당
    private final StringRedisTemplate redisTemplate;
    private final LobbyEventService lobbyEventService;      // 생성 후 목록 브로드캐스트

    // 초대 코드 생성에 사용할 문자셋
    // 숫자 (0~9) + 대문자 (A~Z)에서 혼동하기 쉬운 O, I, 0, 1을 제거하여 UX 개선
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    // SecureRandom : Random보다 예측 불가능한 난수를 생성 -> 코드 추측 공격 방지
    // ThreadLocal이 아닌 단일 인스턴스로 사용해도 SecureRandom은 thread-safe
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // invite_code 생성 재시도 횟수 상한선
    // 6자리 코드의 경우의 수는 32^6 = 약 10억개이므로 3회 시도
    // 3회 초과 시 서버 상태 이상으로 판단하고 예외를 던진다.
    private static final int MAX_RETRY = 3;

    // 로비 생성 메인 로직
    // [처리 순서]
    // 1. invite_code 생성 및 중복 체크 (Redis 1차 -> DB 2차)
    // 2. DB에 로비 저장 (@Transactional 범위)
    // 3. Redis에 로비 정보 저장
    // 4. 공개 로비면 lobby:public Set에 추가
    // 5. 로비 목록 화면 구독자들에게 새로고침 브로드캐스트

    // @param requestDto - 클라이언트가 전달한 로비 생성 요청 데이터
    // @param hostUserId - 로비를 생성하는 방장의 userId (인증 레이어에서 추출)
    // @return - 생성된 로비 정보 및 딥링크를 담은 응답 DTO

    @Transactional
    public LobbyCreateResponseDto createLobby(LobbyCreateRequestDto requestDto, Long hostUserId) {

        // 1. invite_code 생성 및 중복 체크
        String inviteCode = generateUniqueInviteCode();

        // 2. DB에 로비 저장
        GameLobby gameLobby = GameLobby.builder()
                .hostUserId(hostUserId)
                .uuid(UUID.randomUUID().toString())
                .inviteCode(inviteCode)
                .title(requestDto.getTitle())
                .maxPlayers(requestDto.getMaxPlayers())
                .isPrivate(requestDto.getIsPrivate())
                .build();

        GameLobby savedLobby = gameLobbyJpaRepository.save(gameLobby);
        log.info("[LobbyService] DB 저장 완료 - lobbyId : {}, inviteCode : {}", savedLobby.getId(), inviteCode);

        // 3. Redis에 로비 Hash 저장
        // getPublicLobbies()가 읽는 키명과 반드시 일치해야 함
        try {
            saveToRedis(savedLobby);
        } catch (Exception e) {
            // DB 저장은 성공했지만 Redis 저장 실패 시
            // 실시간 목록에는 안 보이지만 DB에는 존재하는 불일치 상태
            // 현재는 로그로 감지하고, 추후 보상 트랜잭션 또는 재시도 로직 도입 가능
            log.error("[LobbyService] Redis 저장 실패 - lobbyId : {}, inviteCode : {}", savedLobby.getId(), inviteCode, e);
        }

        // 5. 로비 목록 화면을 보고 있는 클라이언트들에게 새로고침 신호 전송
        lobbyEventService.notifyLobbyInfoRefresh();

        // 6. 응답 DTO 반환 (딥링크 포함)
        return LobbyCreateResponseDto.builder()
                .lobbyId(savedLobby.getId())
                .inviteCode(inviteCode)
                .title(savedLobby.getTitle())
                .maxPlayers(savedLobby.getMaxPlayers())
                .isPrivate(savedLobby.getIsPrivate())
                .deepLink("/lobby/" + inviteCode)
                .build();
    }

    // 중복되지 않는 invite_code 생성

    // [중복 체크 순서]
    // 1. Redis 1차 체크 : 현재 활성화된 로비는 Redis에 있으므로 DB보다 빠르게 걸러낸다.
    // 2. DB 2차 체크 : Redis에 없더라도 DB에 존재할 수 있으므로 확인

    // [재시도 상한선]
    // MAX_RETRY(3회) 초과 시 서버 상태 이상으로 판단하고 예외 던짐
    // 32^6 ≈ 10억 가지의 경우의 수를 가지므로 실제로 3회를 초과할 가능성은 낮음

    private String generateUniqueInviteCode() {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String code = generateRandomCode();

            // Redis 1차 체크
            if (lobbyRepository.existsByCode(code)) {
                log.warn("[LobbyService] invite_code Redis 중복 감지 ({}회차) : {}", attempt, code);
                continue;
            }

            // DB 2차 체크
            if (gameLobbyJpaRepository.existsByInviteCode(code)) {
                log.warn("[LobbyService] invite_code DB 중복 감지 ({}회차) : {}", attempt, code);
                continue;
            }

            return code;

        }

        // MAX_RETRY 초과 시 서버 상태 이상으로 판단
        throw new IllegalStateException("invite_code 생성 실패 : " + MAX_RETRY + "회 재시도 초과");

    }

    // CODE_CHARS에서 CODE_LENGTH 길이의 랜덤 문자열을 생성
    // SecureRandom을 사용하여 예측 공격을 방지

    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(SECURE_RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    // 생성된 로비 정보를 Redis Hash에 저장

    // [키 구조]
    // - lobby:{inviteCode} : 로비 메타 정보 Hash
    // - lobby:public : 공개 로비 코드 목록 Set

    // Hash 필드명은 LobbyRepositoryImpl.getPublicLobbies()가 읽는 키명과 반드시 일치해야 함.
    // 불일치 시 목록 조회에서 null 반환

    private void saveToRedis(GameLobby lobby) {
        String redisKey = "lobby:" + lobby.getInviteCode();

        // Redis Hash에 로비 메타 정보 저장
        redisTemplate.opsForHash().putAll(redisKey, Map.of(
                "code", lobby.getInviteCode(),
                "host_user_id", String.valueOf(lobby.getHostUserId()),
                "title", lobby.getTitle(),
                "status", lobby.getStatus().name(),
                "max_players", String.valueOf(lobby.getMaxPlayers()),
                "is_private", String.valueOf(lobby.getIsPrivate())
        ));

        log.info("[LobbyService] Redis Hash 저장 완료 - key : {}", redisKey);

        // 공개 로비인 경우에만 lobby:public Set에 추가
        // LobbyRepositoryImpl.getPublicLobbies()가 이 Set을 기준으로 목록 반환
        if (!lobby.getIsPrivate()) {
            redisTemplate.opsForSet().add("lobby:public", lobby.getInviteCode());
            log.info("[LobbyService] 공개 로비 등록 완료 - lobby:public Set에 추가 : {}", lobby.getInviteCode());
        }
    }
}