-- =====================================================================
-- V28: 미분류 유형은 관리자에게만 보인다 (Jira S15P31C106-384 보완, 2026-11-16 결정)
--   V21부터 그룹이 없는(미분류) 객체 유형은 ADMIN을 포함해 아무에게도 보이지 않았다. 그룹은 관리자가 지정하는데(API 024) 지정하기 전에는
--   관리자도 그 유형의 객체, 제안 근거, 후보 시험 표본을 볼 수 없어서, 근거를 못 본 채 승인하거나 검토하려고 먼저 공개해야 했다.
--   미분류를 예약 이름 UNCLASSIFIED로 허용 행과 맞추고(DataScopeRepository), ADMIN에게 그 허용 행을 준다. 역할 이름으로 분기하지 않고
--   허용 행으로만 정한다는 384의 방식 그대로다.
--   - 민감 값은 열지 않는다(can_view_restricted = false).
--   - 업무 역할(PLANNER·VIEWER)에는 행을 주지 않는다. 그룹을 지정해야 보인다.
--   - 외부 모델로 보내는 범위(Agent)에는 이 행이 있어도 미분류 유형을 넣지 않는다(DataAccessPolicy).
--   - UNCLASSIFIED는 유형의 그룹으로 받을 수 없다(ObjectTypeService).
-- =====================================================================

INSERT INTO role_data_scope (role_id, access_group, can_view_restricted)
SELECT r.id, 'UNCLASSIFIED', false
FROM role r
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, access_group) DO NOTHING;

COMMENT ON TABLE role_data_scope IS '역할별 조회 범위(U5, V13 초기값·V21 수정·V28 보완, 384). 모든 역할(ADMIN 포함)은 object_type.access_group이 이 표에 있는 유형만 본다. 그룹이 없는(미분류) 유형은 예약 이름 UNCLASSIFIED의 허용 행을 가진 역할만 보고(V28: ADMIN), 외부 모델로 보내는 범위에는 넣지 않는다. 민감(RESTRICTED) 값은 can_view_restricted=true일 때만 보고, 외부 모델로 보내는 값은 이 값과 관계없이 가린다. 역할 이름으로 나누지 않으므로 역할을 추가하거나 MAINTENANCE를 쓰려면 이 표에 행을 넣는다. 행 단위 제한은 MVP 밖';
