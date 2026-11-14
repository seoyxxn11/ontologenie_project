-- =====================================================================
-- V26 민감에서 내린 속성의 기록 (2026-11-15, S15P31C106-431)
-- 민감 속성을 일반으로 내리면 객체 값만 지운다. 재배정 계획 스냅샷·실행 감사·검사 결과 같은 저장된 사본은 "지금 민감 속성인가"로
-- 가리므로, 내린 뒤에는 그때의 민감 값이 그 유형을 보는 모든 역할에 보였다. 감사 기록은 고치지 않고, 내린 시각을 남겨 그 전에 만든
-- 사본은 그 속성을 민감으로 보고 가린다.
-- =====================================================================

CREATE TABLE property_restriction_history (
    object_type_id   bigint       NOT NULL REFERENCES object_type (id) ON DELETE CASCADE,
    property_code    varchar(100) NOT NULL,
    restricted_until timestamptz  NOT NULL,
    PRIMARY KEY (object_type_id, property_code)
);

COMMENT ON TABLE property_restriction_history IS '민감(RESTRICTED)에서 내린 속성. 마지막으로 내린 시각만 둔다. 이 시각 전에 만든 저장된 사본(계획 스냅샷·실행 감사·검사 결과)은 이 속성을 민감으로 보고 가린다(431)';
COMMENT ON COLUMN property_restriction_history.restricted_until IS '마지막으로 민감에서 내린 시각. 이 전에 저장한 사본의 이 속성 값은 민감 값이다';
