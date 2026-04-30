package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.repository.LobbyRepository;
import io.github.ascrew.monomatbe.service.port.LobbyEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 로비와 관련된 비즈니스 로직 및 실시간 상태 동기화(STOMP 브로드캐스트)를 담당하는 서비스 클래스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyEventService {

  private final LobbyRepository lobbyRepository;
  private final LobbyEventBroadcaster lobbyBroadcaster; // RedisPublisher 대신 인터페이스(Port)에 의존

  public void handlePlayerLeave(String code, String userId) {
    // 인프라에 명령을 내리고 순수한 도메인 결과(Result)를 받음
    LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(code, userId);

    switch (result) {
      case LeaveLobbyResult.Destroyed d -> {
        log.info("[퇴장] 방장 퇴장으로 인한 로비 폭파 - 로비: {}", d.lobbyCode());
        lobbyBroadcaster.broadcastLobbyDestroyed(d.lobbyCode());
      }
      case LeaveLobbyResult.Delegated d -> {
        log.info("[퇴장] 방장 위임 - 로비: {}, 새 방장: {}", d.lobbyCode(), d.newHostId());
        lobbyBroadcaster.broadcastHostDelegated(d.lobbyCode(), d.newHostId());
      }
      case LeaveLobbyResult.Left l -> {
        log.info("[퇴장] 일반 유저 퇴장 - 로비: {}, 유저: {}", l.lobbyCode(), l.userId());
        lobbyBroadcaster.broadcastUserLeft(l.lobbyCode(), l.userId());
      }
      case LeaveLobbyResult.Error e -> log.error("[퇴장 처리 실패] 사유: {}", e.reason());
    }
  }
}