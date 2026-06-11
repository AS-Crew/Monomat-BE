package io.github.ascrew.monomatbe.domain.game.dto;

/**
 * 라운드 종료 원인.
 */
public enum RoundEndReason {
    TIMEOUT,
    SKIP_VOTE,
    HOST_SKIP,
    PLAYBACK_ERROR
}
