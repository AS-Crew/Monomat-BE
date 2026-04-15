package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.repository.LobbyRepository;
import java.security.Principal;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LobbyEventService {

  // 로비 ID 패턴을 임시로 정의하였음. 추후 변경 예정
  private static final Pattern LOBBY_ID_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

  private final SimpMessagingTemplate messagingTemplate;
  private final LobbyRepository lobbyRepository;

  public void notifyLobbyListRefresh(Principal principal) {

    // 필요하면 여기서 principal 기반 권한 검사 추가
    messagingTemplate.convertAndSend("/topic/lobby/refresh", "REFRESH_LOBBY_LIST");
  }

  public void notifyLobbyInfoRefresh(String id, Principal principal) {

    /**
      !! TODO !!
      1. 로비 ID(id)가 패턴에 맞는지 검증
      2. 로비 ID(id)가 Redis에 존재하며 활성화된 로비인지 검증
      3. 요청을 보낸 사용자가 현재 해당 로비에 존재하는지 검증(principal)
    **/

    /** 아래는 예시 코드
    if (!StringUtils.hasText(id) || !LOBBY_ID_PATTERN.matcher(id).matches()) {
      return;
    }

    if (principal == null || !StringUtils.hasText(principal.getName())) {
      return;
    }

    String userId = principal.getName();

    if (!lobbyRepository.existsById(id)) {
      return;
    }

    if (!lobbyRepository.isParticipant(id, userId)) {
      return;
    }
    **/

    messagingTemplate.convertAndSend("/topic/lobby/" + id + "/refresh", "REFRESH_LOBBY_INFO");
  }
}