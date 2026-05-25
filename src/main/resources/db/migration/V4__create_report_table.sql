-- ============================================================================
-- V4__create_report_table.sql
-- 신고 테이블 생성
-- ============================================================================

CREATE TABLE report (
                        id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '신고 고유 식별자',
                        reporter_id  BIGINT       NOT NULL COMMENT '신고자 users.id',
                        lobby_id     BIGINT       NOT NULL COMMENT '신고가 발생한 game_lobby.id',
                        target_type  VARCHAR(30)  NOT NULL COMMENT '신고 대상 타입: LOBBY, LOBBY_USER',
                        target_id    BIGINT       NOT NULL COMMENT '신고 대상 ID: LOBBY=game_lobby.id, LOBBY_USER=users.id',
                        reason       VARCHAR(500) NOT NULL COMMENT '신고 사유',
                        status       VARCHAR(20)  NOT NULL COMMENT '신고 처리 상태: PENDING, RESOLVED, DISMISSED',
                        created_at   DATETIME     NOT NULL COMMENT '신고 접수 일시',
                        resolved_at  DATETIME     NULL COMMENT '신고 처리 완료 일시',

                        CONSTRAINT pk_report PRIMARY KEY (id),

                        CONSTRAINT fk_report_reporter
                            FOREIGN KEY (reporter_id)
                                REFERENCES users (id),

                        CONSTRAINT fk_report_lobby
                            FOREIGN KEY (lobby_id)
                                REFERENCES game_lobby (id)
);

-- ============================================================================
-- 중복 신고 확인용 인덱스
-- ============================================================================
-- 동일 사용자의 동일 로비/동일 대상 PENDING 신고 중복 여부를
-- 서비스 레이어에서 빠르게 조회하기 위한 인덱스다.
-- ============================================================================
CREATE INDEX idx_report_duplicate_check
    ON report (reporter_id, lobby_id, target_type, target_id, status);

-- ============================================================================
-- 조회 성능 인덱스
-- ============================================================================

CREATE INDEX idx_report_target_status
    ON report (target_type, target_id, status);

CREATE INDEX idx_report_lobby_status
    ON report (lobby_id, status);

CREATE INDEX idx_report_status_created_at
    ON report (status, created_at);