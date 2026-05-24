package io.github.ascrew.monomatbe.domain.report.entity;

/**
 * 신고 처리 상태
 *
 * [상태 흐름]
 * PENDING   : 신고가 접수되어 운영자 검토를 기다리는 상태
 * RESOLVED  : 운영자가 신고를 유효한 신고로 처리 완료한 상태
 * DISMISSED : 운영자가 신고를 기각한 상태
 */
public enum ReportStatus {

    /**
     * 신고 접수 직후 기본 상태
     *
     * 동일 사용자의 동일 대상 중복 신고 방지는
     * 기본적으로 PENDING 상태를 기준으로 판단한다.
     */
    PENDING,

    /**
     * 운영자가 신고를 확인하고 처리 완료한 상태
     */
    RESOLVED,

    /**
     * 운영자가 신고를 유효하지 않다고 판단해 기각한 상태
     */
    DISMISSED
}