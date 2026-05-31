-- ============================================================================
-- V12__add_lobby_chat_message_pending_report_unique_key.sql
-- 로비 채팅 메시지 PENDING 신고 중복 방지 유니크 키 추가
-- ============================================================================

ALTER TABLE report
    ADD COLUMN lobby_chat_message_pending_dedup_key VARCHAR(255)
        GENERATED ALWAYS AS (
            CASE
                WHEN status = 'PENDING'
                    AND target_type = 'LOBBY_CHAT_MESSAGE'
                    AND target_reference IS NOT NULL
                    THEN CONCAT(
                        reporter_id,
                        ':',
                        lobby_id,
                        ':',
                        target_type,
                        ':',
                        target_reference
                         )
                ELSE NULL
                END
            ) STORED COMMENT 'LOBBY_CHAT_MESSAGE PENDING 신고 중복 방지 generated key';

CREATE UNIQUE INDEX uq_report_lobby_chat_message_pending_dedup
    ON report (lobby_chat_message_pending_dedup_key);