-- =====================================================================
-- V20: 검사 결과에 비교값으로 바꾸지 못해 뺀 도착 행 수를 남긴다 (S15P31C106-388)
--   규칙을 객체에 적용하는 계산을 검사·후보 시험·연결 재계산이 하나로 쓰게 하면서, 도착 하나가 변환에 실패해도 규칙 전체를
--   멈추지 않고 그 도착만 대응 후보에서 뺀다(연결 재계산과 같은 동작). 뺀 것을 숨기지 않도록 수를 남긴다.
--   출발 행 분류(정상·미연결·중복·조건 위반)와 따로다. ck_check_result_counts는 바꾸지 않는다.
-- =====================================================================

ALTER TABLE check_result ADD COLUMN excluded_target_count integer NOT NULL DEFAULT 0;
ALTER TABLE check_result ADD CONSTRAINT ck_check_result_excluded_targets CHECK (excluded_target_count >= 0);

COMMENT ON COLUMN check_result.excluded_target_count IS '비교값으로 바꾸지 못해(단일 값이 아님·변환 실패) 대응 후보에서 뺀 도착 행 수(388). 출발 행 분류에는 들어가지 않는다';
