/*
로비 관련 이벤트를 처리하는 WebSocket(STOMP) 컨트롤러

클라이언트로부터 전달된 로비 이벤트(생성, 입장/퇴장, 준비 상태 변경 등)를 받아
해당 이벤트를 구독(subscribe) 중인 클라이언트들에게
topic을 통해 메시지를 브로드캐스트하는 역할을 수행한다.
*/

package io.github.ascrew.monomatbe.controller;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class LobbyEventController {

  private final SimpMessagingTemplate messagingTemplate;

  public LobbyEventController(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  // 로비가 생성될 때 로비 리스트을 보고있는 모든 클라이언트에게 로비 리스트를 새로고침하라는 메시지를 보냄
  // 차후 유저의 수가 증가할 경우 많은 요청이 발생할 수 있으므로
  // 일정시간(예: 5초) 동안 1건 이상 로비 생성 이벤트가 발생할 경우에만 로비 리스트 새로고침 메시지를 보내도록 개선할 수 있음
  @MessageMapping("/lobby/create")
  public void notifyLobbyListRefresh() {
    messagingTemplate.convertAndSend("/topic/lobby/refresh", "REFRESH_LOBBY_LIST");
  }

  // 로비 내부 정보가 변경될 때 해당 로비에 참여한 클라이언트들에게 로비 정보를 새로고침하라는 메시지를 보냄
  // 로비 내부 정보 변경의 기준: 유저 입장, 유저 퇴장, 유저 준비, 유저 준비 해제 등
  @MessageMapping("/lobby/{id}/update")
  public void notifyLobbyInfoRefresh(@DestinationVariable String id) {
    messagingTemplate.convertAndSend("/topic/lobby/" + id + "/refresh", "REFRESH_LOBBY_INFO");
  }
}