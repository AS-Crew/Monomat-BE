/*
 * 로비 상태 변경 이벤트를 WebSocket으로 수신하는 컨트롤러.
 *
 * [책임]
 * - 로비 생성 및 로비 내부 정보 변경 이벤트를 수신하여 LobbyEventService에 위임
 * - 컨트롤러는 수신 경로 정의와 서비스 위임만 담당
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.service.LobbyEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class LobbyEventController {

  private final LobbyEventService lobbyEventService;

  /**
   * 로비 생성 이벤트 수신.
   * 로비 리스트를 보고 있는 모든 클라이언트에게 새로고침 신호를 전송합니다.
   *
   * 클라이언트 송신 경로: /app/lobby/create
   *
   * [성능 고려사항]
   * 사용자가 많아질 경우, 매 생성마다 브로드캐스트하면 과부하가 발생할 수 있습니다.
   * 추후 일정 시간(예: 5초) 내 이벤트를 묶어 처리하는 디바운싱 로직 도입을 검토할 수 있습니다.
   */
  @MessageMapping("/lobby/create")
  public void notifyLobbyListRefresh(Principal principal) {
    lobbyEventService.notifyLobbyListRefresh();
  }

  /**
   * 로비 내부 정보 변경 이벤트 수신.
   * 해당 로비에 참여 중인 클라이언트들에게 새로고침 신호를 전송합니다.
   *
   * 클라이언트 송신 경로: /app/lobby/{code}/update
   * 변경 기준: 유저 입장, 퇴장, 준비, 준비 해제, 맵 변경 등
   */
  @MessageMapping("/lobby/{code}/update")
  public void notifyLobbyInfoRefresh(
          @DestinationVariable String code,
          Principal principal
  ) {
    lobbyEventService.notifyLobbyInfoRefresh(code, principal);
  }
}