-- game_lobby: round_count → question_count (idempotent)
SET @sql1 = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE game_lobby RENAME COLUMN round_count TO question_count',
    'SELECT 1')
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'game_lobby'
  AND COLUMN_NAME = 'round_count');

PREPARE v6_stmt1 FROM @sql1;
EXECUTE v6_stmt1;
DEALLOCATE PREPARE v6_stmt1;

-- game_session: total_round_count → total_question_count (idempotent)
SET @sql2 = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE game_session RENAME COLUMN total_round_count TO total_question_count',
    'SELECT 1')
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'game_session'
  AND COLUMN_NAME = 'total_round_count');

PREPARE v6_stmt2 FROM @sql2;
EXECUTE v6_stmt2;
DEALLOCATE PREPARE v6_stmt2;
