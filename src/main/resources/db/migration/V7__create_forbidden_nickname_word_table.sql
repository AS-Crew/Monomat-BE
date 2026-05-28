-- ============================================================================
-- V7__create_forbidden_nickname_word_table.sql
-- 닉네임 금칙어 테이블 생성
-- ============================================================================

CREATE TABLE forbidden_nickname_word (
                                         id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '닉네임 금칙어 고유 식별자',
                                         word            VARCHAR(100) NOT NULL                COMMENT '관리자가 등록한 원본 금칙어',
                                         normalized_word VARCHAR(100) NOT NULL                COMMENT '비교용 정규화 금칙어',
                                         created_at      DATETIME     NOT NULL                COMMENT '금칙어 등록 일시',
                                         updated_at      DATETIME     NOT NULL                COMMENT '금칙어 수정 일시',
                                         CONSTRAINT pk_forbidden_nickname_word PRIMARY KEY (id),
                                         CONSTRAINT uq_forbidden_nickname_word_normalized UNIQUE (normalized_word)
);