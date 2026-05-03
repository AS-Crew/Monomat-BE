package io.github.ascrew.monomatbe.domain.lobby.entity;

/**
 * 로비 상태 열거형.
 * - WAITING  : 게임 시작 전 대기 중
 * - PLAYING  : 게임 진행 중
 * - FINISHED : 게임 종료
 */
public enum LobbyStatus {
    WAITING,
    PLAYING,
    FINISHED
}