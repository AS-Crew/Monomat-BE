package io.github.ascrew.monomatbe.domain.lobby.entity;

import java.time.Duration;

/**
 * 로비 생성 시 사용하는 기본값 및 상수 클래스
 *
 * [기능명세서 기준]
 * - 라운드 수 기본값: 5
 * - 제한 시간 기본값: 30초
 * - 초대 코드 길이: 6자리
 * - 초대 코드 최대 재시도: 5회
 * - 초대 코드 락 TTL: 10초 (생성 실패 시 자동 해제)
 */
public final class LobbyDefaults {

    private LobbyDefaults() {}

    /** 기본 문제 갯수 (맵 미선택 시 적용) */
    public static final int DEFAULT_QUESTION_COUNT = 5;

    /** 기본 문제당 제한 시간 (초) */
    public static final int DEFAULT_TIME_LIMIT_SECONDS = 30;

    /** 초대 코드 길이 */
    public static final int INVITE_CODE_LENGTH = 6;

    /** 초대 코드 생성 최대 재시도 횟수 */
    public static final int INVITE_CODE_MAX_RETRY = 5;

    /** 초대 코드 구성 문자 (대문자 + 숫자) */
    public static final String INVITE_CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * 초대 코드 SETNX 락 TTL.
     * 로비 생성 실패 시 자동 해제되어 코드 공간을 반환한다.
     */
    public static final Duration INVITE_CODE_LOCK_TTL = Duration.ofSeconds(10);
}