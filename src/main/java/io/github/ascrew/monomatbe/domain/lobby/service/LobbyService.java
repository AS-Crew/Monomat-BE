/*
 * 로비 관련 유스케이스 facade 서비스.
 *
 * [현재 책임]
 * - 기존 LobbyController의 의존성을 유지하기 위한 facade 역할
 * - 각 로비 유스케이스 서비스로 요청을 위임
 *
 * [Issue #78 리팩토링 방향]
 * 기존 LobbyService는 로비 생성, 입장, 조회, ready, 시작, 맵 검증,
 * Redis/DB 보상 처리까지 모두 담당하고 있었습니다.
 *
 * 이번 이슈에서는 기능 동작을 바꾸지 않고,
 * 유스케이스 단위 서비스로 책임을 점진적으로 분리합니다.
 *
 * [1단계]
 * - createLobby() 로직을 LobbyCreateService로 분리
 *
 * [2단계]
 * - joinLobby() 로직을 LobbyJoinService로 분리
 *
 * [3단계]
 * - getPublicLobbies(), getLobbyDetail() 로직을 LobbyQueryService로 분리
 *
 * [4단계]
 * - updateReadyStatus() 로직을 LobbyReadyService로 분리
 *
 * [5단계]
 * - startLobbyGame() 로직을 LobbyStartService로 분리
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyReadyRequest;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LobbyService {

    private final LobbyCreateService lobbyCreateService;
    private final LobbyJoinService lobbyJoinService;
    private final LobbyQueryService lobbyQueryService;
    private final LobbyReadyService lobbyReadyService;
    private final LobbyStartService lobbyStartService;

    /**
     * 로비 생성 facade 메서드.
     *
     * 실제 로비 생성 책임은 LobbyCreateService가 담당합니다.
     *
     * @param request   로비 생성 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 생성된 로비 정보 응답 DTO
     */
    public CreateLobbyResponse createLobby(CreateLobbyRequest request, CustomPrincipal principal) {
        return lobbyCreateService.createLobby(request, principal);
    }

    /**
     * 초대 코드 기반 로비 입장 사전 검증 facade 메서드.
     *
     * 실제 입장 사전 검증 책임은 LobbyJoinService가 담당합니다.
     *
     * @param inviteCode 로비 초대 코드
     * @param principal  JWT에서 추출한 인증 주체
     * @return 로비 응답 DTO
     */
    public JoinLobbyResponse joinLobby(String inviteCode, CustomPrincipal principal) {
        return lobbyJoinService.joinLobby(inviteCode, principal);
    }

    /**
     * 공개 로비 목록 조회 facade 메서드.
     *
     * 실제 공개 로비 목록 조회 책임은 LobbyQueryService가 담당합니다.
     *
     * @return 현재 활성화된 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        return lobbyQueryService.getPublicLobbies();
    }

    /**
     * 로비 상세 조회 facade 메서드.
     *
     * 실제 로비 상세 조회 책임은 LobbyQueryService가 담당합니다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     * @return 로비 상세 응답
     */
    public LobbyDetailResponse getLobbyDetail(String code, CustomPrincipal principal) {
        return lobbyQueryService.getLobbyDetail(code, principal);
    }

    /**
     * 로비 ready 상태 변경 facade 메서드.
     *
     * 실제 ready 상태 변경 책임은 LobbyReadyService가 담당합니다.
     *
     * @param code      로비 초대 코드
     * @param request   ready 변경 요청
     * @param principal JWT에서 추출한 인증 주체
     */
    public void updateReadyStatus(
            String code,
            UpdateLobbyReadyRequest request,
            CustomPrincipal principal
    ) {
        lobbyReadyService.updateReadyStatus(code, request, principal);
    }

    /**
     * 로비 게임 시작 facade 메서드.
     *
     * 실제 게임 시작 전 검증 및 WAITING -> PLAYING 전환 책임은 LobbyStartService가 담당합니다.
     *
     * [책임 경계]
     * 이 메서드는 로비 시작 요청을 위임할 뿐입니다.
     * 실제 인게임 세션 생성, 라운드 진행, 문제 송출, 정답 판별은 별도 인게임 도메인에서 처리합니다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     */
    public void startLobbyGame(String code, CustomPrincipal principal) {
        lobbyStartService.startLobbyGame(code, principal);
    }
}