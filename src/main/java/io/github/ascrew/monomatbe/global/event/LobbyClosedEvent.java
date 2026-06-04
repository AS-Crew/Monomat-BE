package io.github.ascrew.monomatbe.global.event;

/**
 * 로비가 폭파(마지막 유저 퇴장으로 완전 삭제)되었을 때 발행되는 이벤트.
 *
 * [위치 선정 이유 — global/event]
 * 발행자는 domain/lobby(LobbyLeaveEventHandler), 수신자는 domain/game이다.
 * 이벤트 객체를 어느 한 도메인에 두면 다른 도메인이 그 도메인에 직접 의존하게 되어
 * 도메인 간 결합이 생긴다. 따라서 global에 위치시켜 domain -> global 단방향 의존을 유지한다.
 *
 * [용도]
 * 게임 도중 전원이 퇴장해 로비가 폭파되면, 게임 세션 Redis 키가 orphan으로 남는다.
 * domain/game의 핸들러가 이 이벤트를 수신해 해당 로비의 게임 세션 키를 정리한다.
 *
 * @param lobbyCode 폭파된 로비의 초대 코드
 */
public record LobbyClosedEvent(
        String lobbyCode
) {}
