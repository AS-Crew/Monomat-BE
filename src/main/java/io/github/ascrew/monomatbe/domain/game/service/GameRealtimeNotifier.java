package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundMetadataDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 인게임 실시간 알림을 담당하는 컴포넌트
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameRealtimeNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 라운드 시작 이벤트를 브로드캐스트합니다.
     */
    public void notifyRoundStart(String lobbyCode, RoundStartDto dto) {
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeGameRound(lobbyCode),
                dto
        );
    }

    /**
     * 라운드 종료(메타데이터) 이벤트를 브로드캐스트합니다.
     */
    public void notifyRoundEnd(String lobbyCode, RoundMetadataDto dto) {
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeGameRoundEnd(lobbyCode),
                dto
        );
    }
}
