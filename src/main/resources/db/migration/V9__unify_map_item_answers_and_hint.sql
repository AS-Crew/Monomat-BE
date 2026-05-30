-- V9: 맵 문제 정답을 단일 JSON 배열(answers)로 통합하고 힌트 정책을 강화한다. (이슈 #120)
--   - 기존 answer(대표) + alt_answers(후보) 이원화를 answers 단일 컬럼(JSON 배열, NOT NULL)으로 통합.
--     answers[0] = 대표 정답.
--   - 힌트는 초성 자동 생성 폐기 → 수동 입력 필수, 최대 50자.
-- 전제: 보존할 운영 데이터 없음. 아래 병합/절단 UPDATE는 개발 DB(baseline 이후 잔여 행) 안전망이다.

-- 1) 기존 행의 answer를 answers 배열로 병합 (alt_answers가 비어 있는 경우)
UPDATE map_item
SET alt_answers = JSON_ARRAY(answer)
WHERE alt_answers IS NULL OR alt_answers = '' OR JSON_LENGTH(alt_answers) = 0;

-- 2) alt_answers → answers 리네임 + NOT NULL 전환
ALTER TABLE map_item
    CHANGE COLUMN alt_answers answers TEXT NOT NULL;

-- 3) 별도 대표 정답 컬럼 제거 (answers[0]로 대체)
ALTER TABLE map_item
    DROP COLUMN answer;

-- 4) 초성 자동 생성 등으로 50자를 초과한 기존 힌트 절단 후 길이 축소
UPDATE map_item
SET hint = LEFT(hint, 50)
WHERE CHAR_LENGTH(hint) > 50;

ALTER TABLE map_item
    MODIFY COLUMN hint VARCHAR(50) NOT NULL;
