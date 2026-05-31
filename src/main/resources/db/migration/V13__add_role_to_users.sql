-- ============================================================================
-- V13__add_role_to_users.sql
-- users 테이블에 서비스 권한(role) 컬럼 추가
-- ============================================================================

SET @sql = (
    SELECT IF(
                   COUNT(*) = 0,
                   'ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''서비스 권한: USER, ADMIN''',
                   'SELECT 1'
           )
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'role'
);

PREPARE v13_stmt FROM @sql;
EXECUTE v13_stmt;
DEALLOCATE PREPARE v13_stmt;