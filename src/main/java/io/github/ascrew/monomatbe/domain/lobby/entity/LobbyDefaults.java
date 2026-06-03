package io.github.ascrew.monomatbe.domain.lobby.entity;

import java.time.Duration;

/**
 * 로비 생성 시 사용하는 기본값 및 제한 정책 상수 클래스
 *
 * [로비 생성 정책]
 * - 최대 인원: 최소 2명, 기본 4명, 최대 8명
 * - 문제 수: 최소 1개, 기본 10개, 최대 50개
 * - 제한 시간: 최소 10초, 기본 30초, 최대 120초
 * - 초대 코드 길이: 6자리
 * - 초대 코드 최대 재시도: 5회
 * - 초대 코드 락 TTL: 10초 (생성 실패 시 자동 해제)
 */
public final class LobbyDefaults {

    private LobbyDefaults() {}

    /** 최소 참여 인원 */
    public static final int MIN_PLAYERS = 2;

    /** 기본 최대 참여 인원 */
    public static final int DEFAULT_MAX_PLAYERS = 4;

    /** 최대 참여 인원 */
    public static final int MAX_PLAYERS = 8;

    /** 최소 문제 갯수 */
    public static final int MIN_QUESTION_COUNT = 1;

    /** 기본 문제 갯수 */
    public static final int DEFAULT_QUESTION_COUNT = 10;

    /** 최대 문제 갯수 */
    public static final int MAX_QUESTION_COUNT = 50;

    /** 최소 문제당 제한 시간 (초) */
    public static final int MIN_TIME_LIMIT_SECONDS = 10;

    /** 기본 문제당 제한 시간 (초) */
    public static final int DEFAULT_TIME_LIMIT_SECONDS = 30;

    /** 최대 문제당 제한 시간 (초) */
    public static final int MAX_TIME_LIMIT_SECONDS = 120;

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