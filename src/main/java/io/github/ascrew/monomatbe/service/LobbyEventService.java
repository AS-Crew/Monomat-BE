package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.regex.Pattern;

/**
 * 로비와 관련된 비즈니스 로직 및 실시간 상태 동기화(STOMP 브로드캐스트)를 담당하는 서비스 클래스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyEventService {

  // 로비 ID 검증 패턴: 영문 대문자 및 숫자 조합 6~12자리
  private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

  private final SimpMessagingTemplate messagingTemplate;
  private final LobbyRepository lobbyRepository;

  // 전역 로비 리스트를 보고 있는 클라이언트들에게 새로고침 신호를 전송한다.
  public void notifyLobbyListRefresh() {
    messagingTemplate.convertAndSend("/topic/lobby/refresh", "REFRESH_LOBBY_LIST");
  }

  // 특정 로비 내부에 있는 유저들에게 로비 설정 변경, 인원 변동 등을 알린다.
  public void notifyLobbyInfoRefresh(String code, Principal principal) {
    // 1. 비정상적인 로비 코드 패턴 차단
    if (!StringUtils.hasText(code) || !LOBBY_CODE_PATTERN.matcher(code).matches()) {
      return;
    }

    // 2. 익명 요청 차단
    if (principal == null || !StringUtils.hasText(principal.getName())) {
      return;
    }

    String userId = principal.getName();

    // 3. Redis 기반 유효성 및 권한 검증 (존재하는 로비인가? 해당 유저가 참여 중인가?)
    if (!lobbyRepository.existsByCode(code)) {
      return;
    }

    if (!lobbyRepository.isParticipant(code, userId)) {
      return;
    }

    messagingTemplate.convertAndSend("/topic/lobby/" + code + "/refresh", "REFRESH_LOBBY_INFO");
  }

  // 유저 퇴장 시나리오 분기 처리
  // Lua 스크립트 실행 결과를 받아, 어떤 범위로 웹소켓 이벤트를 전파할지 결정한다.
  public void handlePlayerLeave(String code, String userId) {
    if (!StringUtils.hasText(code) || !StringUtils.hasText(userId)) return;

    String result;
    try {
      result = lobbyRepository.executeLeaveLobbyProcess(code, userId);
    } catch (Exception e) {
      // Redis 연결 장애 또는 Lua 스크립트 실행 자체가 실패한 경우
      // 브로드캐스트 없이 로그만 남기고 종료 (잘못된 상태 전파 방지)
      log.error("[handlePlayerLeave] Lua 스크립트 실행 실패 - 로비 : {}, 유저 : {}", code, userId, e);
      return;
    }

    if (result == null) {
      // Redis가 응답했지만 스크립트가 값을 반환하지 못한 경우 (예상 밖의 상태)
      log.warn("[handlePlayerLeave] Lua 스크립트 반환값 null - 로비 : {}, 유저 : {}", code, userId);
      return;
    }

    if ("DESTROYED".equals(result)) {
      // 인원이 없어 로비가 완전히 삭제된 경우 -> 로비 리스트 화면 갱신
      log.info("[handlePlayerLeave] 로비 폭파 - 로비 : {}", code);
      notifyLobbyListRefresh();
    } else if (result.startsWith("DELEGATED:")) {
      // 방장이 위임된 경우 -> 방 안 유저들 화면 갱신
      // leave_lobby.lua의 폴백 로직(SRANDMEMBER)까지 포함한 모든 위임 케이스 처리
      String newHost = result.substring("DELEGATED:".length());
      log.info("[handlePlayerLeave] 방장 위임 완료 - 로비 : {}, 새 방장 : {}", code, newHost);
      messagingTemplate.convertAndSend("/topic/lobby/" + code + "/refresh", "REFRESH_LOBBY_INFO");
    } else if ("LEFT".equals(result)) {
      // 일반 유저 퇴장 -> 방 안 유저들 화면 갱신
      log.info("[handlePlayerLeave] 일반 유저 퇴장 - 로비 : {}, 유저 : {}", code, userId);
      messagingTemplate.convertAndSend("/topic/lobby/" + code + "/refresh", "REFRESH_LOBBY_INFO");
    } else {
      // Lua 스크립트가 예상치 못한 값을 반환한 경우 (버그 추적용)
      log.warn("[handlePlayerLeave] 알 수 없는 Lua 반환값 : {} - 로비 : {}, 유저 : {}", result, code, userId);
    }
  }
}