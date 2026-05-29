-- ============================================================================
-- V8__create_admin_users_table.sql
-- 관리자 사용자 테이블 생성
-- ============================================================================

CREATE TABLE admin_users (
                             id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '관리자 권한 고유 식별자',
                             user_id     BIGINT   NOT NULL                COMMENT '관리자 권한을 가진 users.id',
                             created_at  DATETIME NOT NULL                COMMENT '관리자 권한 등록 일시',
                             updated_at  DATETIME NOT NULL                COMMENT '관리자 권한 수정 일시',
                             CONSTRAINT pk_admin_users PRIMARY KEY (id),
                             CONSTRAINT uq_admin_users_user_id UNIQUE (user_id),
                             CONSTRAINT fk_admin_users_user_id FOREIGN KEY (user_id) REFERENCES users (id)
);