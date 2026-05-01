/*
 * 로비 이벤트 비즈니스 로직 및 실시간 상태 동기화(STOMP 브로드캐스트)를 담당하는 서비스.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;

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
    messagingTemplate.convertAndSend(StompDestinations.SUBSCRIBE_LOBBY_LIST_REFRESH, "REFRESH_LOBBY_LIST");
  }

  /**
   * 특정 로비 내부의 클라이언트들에게 새로고침 신호를 전송합니다.
   * 로비 설정 변경, 인원 변동 등의 이벤트 발생 시 호출됩니다.
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

    // principal.getName()이 사용자 식별자임을 명확히 변수명으로 표현
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

  public void handlePlayerLeave(String code, String userIdentifier) {
    if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) return;

    String result;
    try {
      result = lobbyRepository.executeLeaveLobbyProcess(code, userIdentifier);
    } catch (Exception e) {
      log.error("[handlePlayerLeave] Lua 스크립트 실행 실패 - 로비: {}, 식별자: {}",
              code, userIdentifier, e);
      return;
    }

    if (result == null) {
      log.warn("[handlePlayerLeave] Lua 스크립트 반환값 null - 로비: {}, 식별자: {}",
              code, userIdentifier);
      return;
    }

    if ("DESTROYED".equals(result)) {
      log.info("[handlePlayerLeave] 로비 폭파 - 로비: {}", code);
      notifyLobbyListRefresh();

    } else if (result.startsWith("DELEGATED:")) {
      String newHostIdentifier = result.substring("DELEGATED:".length());
      log.info("[handlePlayerLeave] 방장 위임 - 로비: {}, 새 방장: {}", code, newHostIdentifier);
      messagingTemplate.convertAndSend(
              StompDestinations.subscribeLobbyRefresh(code), "REFRESH_LOBBY_INFO");

    } else if ("LEFT".equals(result)) {
      log.info("[handlePlayerLeave] 일반 퇴장 - 로비: {}, 식별자: {}", code, userIdentifier);
      messagingTemplate.convertAndSend(
              StompDestinations.subscribeLobbyRefresh(code), "REFRESH_LOBBY_INFO");

    } else {
      log.warn("[handlePlayerLeave] 알 수 없는 Lua 반환값: {} - 로비: {}, 식별자: {}",
              result, code, userIdentifier);
    }
  }
}