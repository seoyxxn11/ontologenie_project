-- =====================================================================
-- V20: 역할별 조회 범위 바로잡기 (Jira S15P31C106-384, 2026-11-13 결정)
--   1. 정의·시스템 관리와 값 열람을 나눈다. ADMIN은 코드에서 모두 허용하던 것을 없애고, 다른 역할처럼 허용 행으로 정한다.
--      ADMIN은 운영 그룹(PRODUCTION·SALES_SUPPLY)을 보고 민감 값은 보지 않는다.
--   2. V13의 민감 값 열람이 PLANNER·VIEWER 사이에 뒤바뀌어 있었다. 민감 값은 업무 Action을 실행하는 PLANNER만 본다.
--   3. MAINTENANCE는 역할은 두되 권한·범위가 없는 미구성 역할로 둔다(결정 11에서 VIEWER와 같았고 계정 0개). 쓰게 되면 허용 행을 넣는다.
--   그룹이 없는(미분류) 객체 유형은 어느 역할에도 보이지 않는다. API 024로 그룹을 지정해야 보인다.
-- =====================================================================

UPDATE role_data_scope s
SET can_view_restricted = (r.code = 'PLANNER')
FROM role r
WHERE r.id = s.role_id
  AND r.code IN ('PLANNER', 'VIEWER')
  AND s.access_group IN ('PRODUCTION', 'SALES_SUPPLY');

DELETE FROM role_data_scope s
USING role r
WHERE r.id = s.role_id
  AND r.code = 'MAINTENANCE';

INSERT INTO role_data_scope (role_id, access_group, can_view_restricted)
SELECT r.id, v.access_group, false
FROM role r
CROSS JOIN (VALUES ('PRODUCTION'), ('SALES_SUPPLY')) AS v (access_group)
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, access_group) DO NOTHING;

COMMENT ON TABLE role_data_scope IS '역할별 조회 범위(U5, V13 초기값·V20 수정, 384). 모든 역할(ADMIN 포함)은 object_type.access_group이 이 표에 있는 유형만 본다. 그룹이 없는 유형은 아무도 보지 않는다. 민감(RESTRICTED) 값은 can_view_restricted=true일 때만 보고, 외부 모델로 보내는 값은 이 값과 관계없이 가린다. 역할 이름으로 나누지 않으므로 역할을 추가하거나 MAINTENANCE를 쓰려면 이 표에 행을 넣는다. 행 단위 제한은 MVP 밖';
