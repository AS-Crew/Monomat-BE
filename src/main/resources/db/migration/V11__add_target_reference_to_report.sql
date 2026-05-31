-- ============================================================================
-- V11__add_target_reference_to_report.sql
-- 문자열 기반 신고 대상 식별자 컬럼 추가
-- ============================================================================

ALTER TABLE report
    ADD COLUMN target_reference VARCHAR(100) NULL COMMENT '문자열 기반 신고 대상 식별자. 예: LOBBY_CHAT_MESSAGE의 messageId'
    AFTER target_id;

CREATE INDEX idx_report_target_reference
    ON report (target_type, target_reference, status);