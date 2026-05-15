-- =====================================================================
-- V2: map_item 활성 행 (map_id, order_num) 동시성 보호
--
-- 배경:
--   기존에는 서비스 레이어에서 exists 체크 → save 순서로 중복을 막았으나,
--   exists와 save 사이의 race window 동안 동일 (map_id, order_num)이 두 번
--   INSERT 될 수 있었음. soft delete를 사용하므로 단순 UNIQUE(map_id, order_num)은
--   소프트 삭제된 행과 충돌해 사용 불가.
--
-- 해결:
--   MySQL 8 generated column 으로 활성(is_deleted=FALSE) 행만 order_num을 보존하고
--   soft delete 된 행은 NULL 로 만든다. NULL은 UNIQUE 충돌 대상이 아니므로
--   소프트 삭제된 동일 순서 데이터는 자유롭게 공존할 수 있다.
--
-- 사전 점검 (배포 전 운영 DB에서 실행해야 함):
--   SELECT map_id, order_num, COUNT(*) AS cnt
--     FROM map_item
--    WHERE is_deleted = FALSE
--    GROUP BY map_id, order_num
--   HAVING cnt > 1;
--   결과가 0행이어야 ALTER가 성공한다. 1행 이상이면 데이터 정리 후 재시도.
-- =====================================================================

ALTER TABLE `map_item`
    ADD COLUMN `active_order_num` INT GENERATED ALWAYS AS (
        CASE WHEN `is_deleted` = FALSE THEN `order_num` ELSE NULL END
    ) STORED;

ALTER TABLE `map_item`
    ADD CONSTRAINT `uq_map_item_active_order`
    UNIQUE (`map_id`, `active_order_num`);
