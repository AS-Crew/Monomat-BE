/*
 * 로비 이벤트 비즈니스 로직 및 실시간 상태 동기화(STOMP 브로드캐스트)를 담당하는 서비스.
 *
 * [LeaveLobbyResult sealed interface 도입 이유]
 * 기존 String 반환값 방식은 서비스 레이어에서 "DELEGATED:" 같은
 * Redis 내부 포맷 문자열을 직접 파싱해야 했습니다.
 * sealed interface + switch 패턴 매칭으로 변경하여
 * 컴파일러가 모든 케이스 처리를 검증하도록 개선합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyEventService {

  // 로비 코드 검증 패턴: 영문 대문자 및 숫자 조합 6~12자리
  private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

  private final SimpMessagingTemplate messagingTemplate;
  private final LobbyRepository lobbyRepository;
  private final StringRedisTemplate redisTemplate;

  /**
   * 전역 로비 리스트를 보고 있는 클라이언트들에게 새로고침 신호를 전송합니다.
   * 로비 생성 또는 삭제(폭파) 이벤트 발생 시 호출됩니다.
   */
  public void notifyLobbyListRefresh() {
    messagingTemplate.convertAndSend(
            StompDestinations.SUBSCRIBE_LOBBY_LIST_REFRESH, "REFRESH_LOBBY_LIST");
  }

  /**
   * 특정 로비 내부의 클라이언트들에게 새로고침 신호를 전송합니다.
   *
   * [검증 순서]
   * 1. 로비 코드 형식 검증
   * 2. 요청자 인증 확인
   * 3. Redis에서 로비 존재 여부 확인
   * 4. 요청자가 해당 로비의 참여자인지 확인
   */
  public void notifyLobbyInfoRefresh(String code, Principal principal) {
    if (!StringUtils.hasText(code) || !LOBBY_CODE_PATTERN.matcher(code).matches()) {
      return;
    }

    if (principal == null || !StringUtils.hasText(principal.getName())) {
      return;
    }

    String userIdentifier = principal.getName();

    if (!lobbyRepository.existsByCode(code)) {
      return;
    }

    if (!lobbyRepository.isParticipant(code, userIdentifier)) {
      return;
    }

    messagingTemplate.convertAndSend(
            StompDestinations.subscribeLobbyRefresh(code), "REFRESH_LOBBY_INFO");
  }

  /**
   * 유저 퇴장 시나리오를 분기 처리합니다.
   *
   * [기존 방식과의 차이]
   * 기존: "DESTROYED".equals(result), result.startsWith("DELEGATED:") 등 문자열 비교
   *       → 서비스가 Redis 반환 포맷을 직접 알아야 하며 오타 위험 존재
   * 변경: switch 패턴 매칭으로 타입 안전하게 처리
   *       → 새로운 LeaveLobbyResult 구현체 추가 시 컴파일 오류로 누락 방지
   *
   * [처리 분기]
   * - Destroyed : 로비 폭파 → 전역 로비 리스트 새로고침
   * - Delegated : 방장 위임 → 해당 로비 내부 새로고침
   * - Left      : 일반 퇴장 → 해당 로비 내부 새로고침
   * - Error     : 처리 실패 → 브로드캐스트 없이 에러 로그만 기록
   */
  public void handlePlayerLeave(String code, String userIdentifier) {
    if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) return;

    LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(code, userIdentifier);

    // sealed interface + switch 패턴 매칭
    // 컴파일러가 모든 permits 구현체(Destroyed, Delegated, Left, Error)의
    // 처리 여부를 검증합니다. 케이스 누락 시 컴파일 오류 발생.
    switch (result) {
      case LeaveLobbyResult.Destroyed d -> {
        log.info("[handlePlayerLeave] 로비 폭파 - 로비: {}", d.lobbyCode());
        notifyLobbyListRefresh();
      }
      case LeaveLobbyResult.Delegated d -> {
        log.info("[handlePlayerLeave] 방장 위임 - 로비: {}, 새 방장: {}",
                d.lobbyCode(), d.newHostId());
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(d.lobbyCode()),
                "REFRESH_LOBBY_INFO");
      }
      case LeaveLobbyResult.Left l -> {
        log.info("[handlePlayerLeave] 일반 퇴장 - 로비: {}, 식별자: {}",
                l.lobbyCode(), l.userId());
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(l.lobbyCode()),
                "REFRESH_LOBBY_INFO");
      }
      case LeaveLobbyResult.Error e -> {
        // Error는 정상 흐름이 아니므로 브로드캐스트 없이 로그만 남깁니다.
        log.error("[handlePlayerLeave] 퇴장 처리 실패 - 사유: {}", e.reason());
      }
    }
  }

  /**
   * WebSocket 세션과 사용자/로비 정보를 Redis에 매핑하여 저장합니다.
   * 유저가 로비에 입장하여 WebSocket 연결이 성공했을 때 호출합니다.
   *
   * [LobbyConnectionListener에서 이전된 이유]
   * 세션 저장은 WebSocket 인프라 관심사가 아닌 로비 비즈니스 로직의 일부입니다.
   * 서비스 레이어에서 관리하는 것이 레이어 책임 원칙에 부합합니다.
   *
   * @param wsSessionId    WebSocket 고유 세션 ID
   * @param userIdentifier 사용자 식별자 (게스트 UUID or 회원 ID)
   * @param lobbyCode      입장한 로비의 초대 코드
   */
  public void saveConnectionInfo(String wsSessionId, String userIdentifier, String lobbyCode) {
    Map<String, String> data = Map.of(
            "userId", userIdentifier,
            "lobbyCode", lobbyCode
    );

    // TTL 설정으로 좀비 세션 데이터 방지
    redisTemplate.opsForHash().putAll(RedisKeys.wsConnectionKey(wsSessionId), data);
    redisTemplate.expire(RedisKeys.wsConnectionKey(wsSessionId), Duration.ofDays(1));
  }
}