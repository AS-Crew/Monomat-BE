/*
 * 로비 상태 변경 이벤트를 WebSocket으로 수신하는 컨트롤러
 *
 * [책임]
 * - STOMP MessageMapping 경로 정의
 * - STOMP 세션 속성에서 인증된 userIdentifier 추출
 * - 로비 목록/상세 refresh 요청은 LobbyRealtimeNotifier로 위임
 * - 강퇴 요청은 LobbyKickService로 위임
 *
 * [주의]
 * 기존 STOMP destination 계약은 변경하지 않는다.
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;

import io.github.ascrew.monomatbe.domain.lobby.dto.KickLobbyPlayerRequest;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyKickService;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyRealtimeNotifier;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class LobbyEventController {

  private final LobbyRealtimeNotifier lobbyRealtimeNotifier;
  private final LobbyKickService lobbyKickService;

  /**
   * 로비 생성 이벤트 수신
   *
   * 클라이언트 송신 경로: /app/lobby/create
   *
   * [동작]
   * 로비 리스트를 보고 있는 클라이언트에게 목록 refresh 신호를 보낸다.
   */
  @MessageMapping("/lobby/create")
  public void notifyLobbyListRefresh(Principal principal) {
    lobbyRealtimeNotifier.notifyLobbyListRefresh();
  }

  /**
   * 로비 내부 정보 변경 이벤트 수신
   *
   * 클라이언트 송신 경로: /app/lobby/{code}/update
   *
   * [동작]
   * 요청자가 해당 로비 참여자인 경우에만 로비 내부 refresh 신호를 보낸다.
   */
  @MessageMapping("/lobby/{code}/update")
  public void notifyLobbyInfoRefresh(
          @DestinationVariable String code,
          SimpMessageHeaderAccessor accessor
  ) {
    String userIdentifier = extractUserIdentifier(accessor);
    lobbyRealtimeNotifier.notifyLobbyInfoRefresh(code, userIdentifier);
  }

  /**
   * 방장의 로비 유저 강퇴 이벤트 수신
   *
   * 클라이언트 송신 경로: /app/lobby/{code}/kick
   *
   * [동작]
   * 실제 강퇴 검증, Lua 실행, KICK 메시지 발행은 LobbyKickService가 담당한다.
   */
  @MessageMapping("/lobby/{code}/kick")
  public void kickLobbyPlayer(
          @DestinationVariable String code,
          @Valid @Payload KickLobbyPlayerRequest request,
          SimpMessageHeaderAccessor accessor
  ) {
    String requesterIdentifier = extractUserIdentifier(accessor);
    lobbyKickService.kickLobbyPlayer(code, request, requesterIdentifier);
  }

  /**
   * STOMP 세션 속성에서 인증된 userIdentifier를 추출한다.
   *
   * [설계 의도]
   * StompChannelInterceptor가 CONNECT 시점에 검증한 userIdentifier를
   * sessionAttributes에 저장하므로, MessageMapping에서는 Principal 대신
   * 해당 세션 속성을 신뢰 기준으로 사용한다.
   */
  private String extractUserIdentifier(SimpMessageHeaderAccessor accessor) {
    if (accessor == null) {
      return null;
    }

    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

    if (sessionAttributes == null) {
      return null;
    }

    Object value = sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);

    if (value instanceof String userIdentifier && StringUtils.hasText(userIdentifier)) {
      return userIdentifier;
    }

    return null;
  }
}