-- 이슈 #168: 문제별 종료시간 제거.
-- 재생 구간 길이는 로비의 time_limit_seconds(시작 시간으로부터 동일 재생시간)로 통일되어
-- map_item.end_time 은 더 이상 사용되지 않는다.

-- 기존 운영 데이터의 맵 단위 총 재생시간은 더 이상 계산/사용되지 않으므로 0으로 정규화한다.
UPDATE `map`
SET `total_play_time` = 0
WHERE `total_play_time` <> 0;

ALTER TABLE `map_item` DROP COLUMN `end_time`;
