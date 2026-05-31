-- ============================================================================
-- V13__add_role_to_users.sql
-- users 테이블에 서비스 권한(role) 컬럼 추가
-- ============================================================================

ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
    COMMENT '서비스 권한: USER, ADMIN';