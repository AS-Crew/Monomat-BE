/*
 * 로비 이벤트 비즈니스 로직 및 실시간 상태 동기화(STOMP 브로드캐스트)를 담당하는 서비스.
 *
 * saveConnectionInfo() 제거
 * - WebSocket 세션과 로비 코드를 Redis에 매핑하는 로직은 순수 인프라 책임이므로 domain 서비스에 위치하는 것이 부적절함
 * - WebSocketEventListener(global)로 이전하여 global -> domain 의존 방향 역전을 방지한다.
 * - 함께 사용하던 WS_CONNECTION_TTL 상수도 WebSocketEventListener로 이전한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyEventService {

  // 로비 코드 검증 패턴: 영문 대문자 및 숫자 조합 6~12자리
  private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

  private final SimpMessagingTemplate messagingTemplate;
  private final LobbyRepository lobbyRepository;

  /**
   * 전역 로비 리스트를 보고 있는 클라이언트들에게 새로고침 신호를 전송합니다.
   * 로비 생성 또는 삭제(폭파) 이벤트 발생 시 호출됩니다.
   */
  public void notifyLobbyListRefresh() {
    messagingTemplate.convertAndSend(
            StompDestinations.SUBSCRIBE_LOBBY_LIST_REFRESH,
            StompDestinations.MSG_REFRESH_LOBBY_LIST);
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
            StompDestinations.subscribeLobbyRefresh(code),
            StompDestinations.MSG_REFRESH_LOBBY_INFO);
  }

  /**
   * 플레이어 퇴장 이벤트를 수신하여 퇴장 처리를 수행합니다.
   *
   * [처리 분기 — sealed interface + switch 패턴 매칭]
   * - Destroyed : 로비 폭파 → 전역 로비 리스트 새로고침
   * - Delegated : 방장 위임 → 해당 로비 내부 새로고침
   * - Left      : 일반 퇴장 → 해당 로비 내부 새로고침
   * - Error     : 처리 실패 → 브로드캐스트 없이 에러 로그만 기록
   *
   * @param event WebSocketEventListener가 발행한 PlayerLeaveEvent
   */
  @EventListener
  public void handlePlayerLeave(PlayerLeaveEvent event) {
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
                StompDestinations.MSG_REFRESH_LOBBY_INFO);
      }
      case LeaveLobbyResult.Left l -> {
        log.info("[handlePlayerLeave] 일반 퇴장 - 로비: {}, 식별자: {}",
                l.lobbyCode(), l.userId());
        // 참여자가 줄었으므로 로비 내부 클라이언트에게 새로고침 신호
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(l.lobbyCode()),
                StompDestinations.MSG_REFRESH_LOBBY_INFO);
      }
      case LeaveLobbyResult.Error e -> {
        // 정상 흐름이 아니므로 브로드캐스트 없이 에러 로그만 남깁니다.
        log.error("[handlePlayerLeave] 퇴장 처리 실패 - 사유: {}", e.reason());
      }
    }
  }
}