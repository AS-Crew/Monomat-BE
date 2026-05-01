/*
 * 로비 조회 관련 비즈니스 로직을 담당하는 서비스.
 *
 * [LobbyController에서 Repository 직접 참조를 제거한 이유]
 * 컨트롤러가 Repository를 직접 참조하면 레이어 경계가 무너집니다.
 * 서비스 레이어를 경유함으로써 향후 캐싱, 트랜잭션, 추가 비즈니스 로직을
 * 적용할 수 있는 확장 지점을 확보합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyService {

    private final LobbyRepository lobbyRepository;

    /**
     * 공개 로비 목록을 조회합니다.
     * Redis에서 직접 필터링하여 공개(isPrivate=false) 로비만 반환합니다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyRepository.getPublicLobbies();
    }
}