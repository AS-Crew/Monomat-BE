/*
 * 로비 이벤트 비즈니스 로직 및 실시간 상태 동기화(STOMP 브로드캐스트)를 담당하는 서비스.
 *
 * [리팩토링 변경 사항 — 의존 방향 역전 해결]
 * 기존: WebSocketEventListener(global)에서 handlePlayerLeave()를 직접 호출
 *       → global이 domain을 직접 참조하는 의존 방향 역전 문제 존재
 *
 * 변경: handlePlayerLeave(PlayerLeaveEvent)에 @EventListener 추가
 *       → WebSocketEventListener가 PlayerLeaveEvent를 발행하면
 *          Spring이 이 메서드로 자동 전달
 *       → global은 domain을 전혀 알 필요 없어짐
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
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
   *
   * @param code      로비 초대 코드
   * @param principal 요청자 인증 정보
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
   * 플레이어 퇴장 이벤트를 수신하여 퇴장 처리를 수행합니다.
   *
   * [수정 — 의존 방향 역전 해결]
   * 기존: public void handlePlayerLeave(String code, String userIdentifier)
   *       → WebSocketEventListener(global)에서 직접 호출
   *       → global → domain 의존 방향 역전 문제 존재
   *
   * 변경: @EventListener public void handlePlayerLeave(PlayerLeaveEvent event)
   *       → WebSocketEventListener가 PlayerLeaveEvent를 발행하면
   *          Spring ApplicationEventPublisher가 이 메서드로 자동 전달
   *       → WebSocketEventListener는 LobbyEventService를 전혀 알 필요 없음
   *       → 의존 방향: domain → global (PlayerLeaveEvent) 로 정상화
   *
   * [처리 분기 — sealed interface + switch 패턴 매칭]
   * - Destroyed : 로비 폭파 → 전역 로비 리스트 새로고침
   * - Delegated : 방장 위임 → 해당 로비 내부 새로고침
   * - Left      : 일반 퇴장 → 해당 로비 내부 새로고침
   * - Error     : 처리 실패 → 브로드캐스트 없이 에러 로그만 기록
   *
   * 컴파일러가 모든 permits 구현체의 처리 여부를 검증합니다.
   * 케이스 누락 시 컴파일 오류가 발생합니다.
   *
   * @param event WebSocketEventListener가 발행한 PlayerLeaveEvent
   */
  @EventListener
  public void handlePlayerLeave(PlayerLeaveEvent event) {
    // 이벤트 객체에서 필요한 값 추출
    String code = event.lobbyCode();
    String userIdentifier = event.userIdentifier();

    // 입력값 방어: 비어있는 코드나 식별자는 처리하지 않음
    if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) return;

    // Lua 스크립트로 원자적 퇴장 처리 후 결과를 도메인 객체로 수신
    LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(code, userIdentifier);

    // sealed interface + switch 패턴 매칭
    // 컴파일러가 모든 permits 구현체(Destroyed, Delegated, Left, Error)의
    // 처리 여부를 검증합니다. 케이스 누락 시 컴파일 오류 발생.
    switch (result) {
      case LeaveLobbyResult.Destroyed d -> {
        log.info("[handlePlayerLeave] 로비 폭파 - 로비: {}", d.lobbyCode());
        // 로비가 사라졌으므로 전역 로비 리스트를 보고 있는 클라이언트에게 새로고침 신호
        notifyLobbyListRefresh();
      }
      case LeaveLobbyResult.Delegated d -> {
        log.info("[handlePlayerLeave] 방장 위임 - 로비: {}, 새 방장: {}",
                d.lobbyCode(), d.newHostId());
        // 방장이 바뀌었으므로 로비 내부 클라이언트에게 새로고침 신호
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(d.lobbyCode()),
                "REFRESH_LOBBY_INFO");
      }
      case LeaveLobbyResult.Left l -> {
        log.info("[handlePlayerLeave] 일반 퇴장 - 로비: {}, 식별자: {}",
                l.lobbyCode(), l.userId());
        // 참여자가 줄었으므로 로비 내부 클라이언트에게 새로고침 신호
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(l.lobbyCode()),
                "REFRESH_LOBBY_INFO");
      }
      case LeaveLobbyResult.Error e -> {
        // 정상 흐름이 아니므로 브로드캐스트 없이 에러 로그만 남깁니다.
        log.error("[handlePlayerLeave] 퇴장 처리 실패 - 사유: {}", e.reason());
      }
    }
  }

  /**
   * WebSocket 세션과 사용자/로비 정보를 Redis에 매핑하여 저장합니다.
   * 유저가 로비에 입장하여 WebSocket 연결이 성공했을 때 호출합니다.
   *
   * [저장 목적]
   * WebSocketEventListener의 handleDisconnectEvent에서
   * wsSessionId만으로 lobbyCode와 userIdentifier를 역추적하는 데 사용됩니다.
   *
   * @param wsSessionId    WebSocket 고유 세션 ID
   * @param userIdentifier 사용자 식별자 (게스트 UUID 또는 회원 ID)
   * @param lobbyCode      입장한 로비의 초대 코드
   */
  public void saveConnectionInfo(String wsSessionId, String userIdentifier, String lobbyCode) {
    Map<String, String> data = Map.of(
            "userId", userIdentifier,
            "lobbyCode", lobbyCode
    );

    // TTL 설정으로 비정상 종료 시 좀비 세션 데이터 자동 만료 처리
    redisTemplate.opsForHash().putAll(RedisKeys.wsConnectionKey(wsSessionId), data);
    redisTemplate.expire(RedisKeys.wsConnectionKey(wsSessionId), Duration.ofDays(1));
  }
}