-- ============================================================================
-- V10__create_lobby_chat_message_report_snapshot_table.sql
-- 로비 채팅 메시지 신고 스냅샷 테이블 생성
-- ============================================================================

CREATE TABLE lobby_chat_message_report_snapshot (
                                                    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '채팅 메시지 신고 스냅샷 고유 식별자',
                                                    report_id         BIGINT       NOT NULL COMMENT '연결된 report.id',
                                                    message_id        VARCHAR(64)  NOT NULL COMMENT 'Redis 최근 채팅 메시지 식별자',
                                                    sender_identifier VARCHAR(100) NOT NULL COMMENT 'Redis/WebSocket 사용자 식별자',
                                                    sender_id         BIGINT       NULL COMMENT '발신자 users.id, 조회 실패 또는 과거 payload 호환성으로 NULL 허용',
                                                    sender_nickname   VARCHAR(50)  NULL COMMENT '신고 시점의 발신자 닉네임',
                                                    content           VARCHAR(500) NOT NULL COMMENT '신고 시점의 채팅 메시지 본문',
                                                    message_type      VARCHAR(30)  NOT NULL COMMENT '신고 시점의 메시지 타입',
                                                    sent_at           DATETIME(3)  NOT NULL COMMENT '메시지 발신 시각',
                                                    created_at        DATETIME(3)  NOT NULL COMMENT '스냅샷 저장 시각',

                                                    CONSTRAINT pk_lobby_chat_message_report_snapshot
                                                        PRIMARY KEY (id),

                                                    CONSTRAINT uq_lobby_chat_message_report_snapshot_report
                                                        UNIQUE (report_id),

                                                    CONSTRAINT fk_lobby_chat_message_snapshot_report
                                                        FOREIGN KEY (report_id)
                                                            REFERENCES report (id),

                                                    CONSTRAINT fk_lobby_chat_message_snapshot_sender
                                                        FOREIGN KEY (sender_id)
                                                            REFERENCES users (id)
);

-- ============================================================================
-- 조회 성능 인덱스
-- ============================================================================

CREATE INDEX idx_lobby_chat_message_snapshot_message_id
    ON lobby_chat_message_report_snapshot (message_id);

CREATE INDEX idx_lobby_chat_message_snapshot_sender_id
    ON lobby_chat_message_report_snapshot (sender_id);

CREATE INDEX idx_lobby_chat_message_snapshot_sent_at
    ON lobby_chat_message_report_snapshot (sent_at);

-- ============================================================================
-- 중복 신고 확인용 인덱스
-- ============================================================================
-- 동일 사용자의 동일 로비/동일 messageId PENDING 신고 여부는
-- report 테이블과 snapshot 테이블을 join해 확인한다.
-- snapshot 측에서는 message_id + report_id 인덱스가 join 범위를 줄인다.
-- ============================================================================

CREATE INDEX idx_lobby_chat_message_snapshot_duplicate_check
    ON lobby_chat_message_report_snapshot (message_id, report_id);