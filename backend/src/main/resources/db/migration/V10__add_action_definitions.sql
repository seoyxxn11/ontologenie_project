-- =====================================================================
-- V10: 권한 코드와 Action 정의 초기 데이터 (Jira S15P31C106-206, Action 정의서 3·5절)
--   계정 관리 분(권한 ACCOUNT_MANAGE, 정의 3행)은 V7이 넣었으므로 여기서는 나머지만 넣는다. 코드가 UNIQUE라 다시 넣으면 적용이 실패한다.
--   MAINTENANCE는 MVP에서 VIEWER와 같아(결정 11) 권한을 주지 않는다.
-- =====================================================================

INSERT INTO permission (code, name, description) VALUES
    ('PLAN_CREATE',              '재배정 계획 생성',  '재배정 계획 생성 Action 실행 (API 091)'),
    ('PROPOSAL_DECIDE',          '후보 결정',        '후보 승인·거절·보류 (API 054)'),
    ('SOURCE_MANAGE',            '원천 관리',        '원천 등록·변경 (API 010·012)'),
    ('ACTION_DEFINITION_MANAGE', 'Action 정의 관리', 'Action 정의 등록·수정 (API 088·090)'),
    ('SYNC_SCHEDULE_MANAGE',     '수집 주기 관리',    '원천별 수집 주기 설정 (API 072)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code IN ('PLAN_CREATE', 'PROPOSAL_DECIDE', 'SOURCE_MANAGE',
                                'ACTION_DEFINITION_MANAGE', 'SYNC_SCHEDULE_MANAGE')
WHERE r.code = 'ADMIN';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code = 'PLAN_CREATE'
WHERE r.code = 'PLANNER';

-- 업무 Action. 대상 객체 유형은 관리자가 설비 유형을 만든 뒤 090으로 지정할 수 있다(비워도 Handler가 유형을 검사한다).
INSERT INTO action_definition (
    code, name, description, target_kind, action_mode, handler_key, input_schema, action_kind,
    requires_confirmation, required_permission
) VALUES (
    'CREATE_REASSIGNMENT_PLAN', '재배정 계획 생성',
    '정지 설비의 작업지시·공정을 대체 설비로 옮기는 계획을 저장한다. 원천 MES 배정은 바꾸지 않는다.',
    'OBJECT', 'EXECUTE', 'CREATE_REASSIGNMENT_PLAN',
    '{
       "type": "object",
       "required": ["fromEquipmentId", "items", "dataAsOf"],
       "properties": {
         "fromEquipmentId": {"type": "integer", "minimum": 1},
         "items": {
           "type": "array",
           "minItems": 1,
           "items": {
             "type": "object",
             "required": ["workOrderId", "toEquipmentId"],
             "properties": {
               "workOrderId": {"type": "integer", "minimum": 1},
               "operationCode": {"type": ["string", "null"], "maxLength": 50},
               "toEquipmentId": {"type": "integer", "minimum": 1}
             }
           }
         },
         "selectionBasis": {"type": "object"},
         "dataAsOf": {"type": "string", "format": "date-time"}
       }
     }'::jsonb,
    'WRITE', false, 'PLAN_CREATE'
);

-- 관리 Action. 각 도메인 API가 직접 실행하고, 정의는 감사 기록을 연결하려고 둔다.
INSERT INTO action_definition (
    code, name, description, target_kind, action_mode, action_kind, requires_confirmation, required_permission
) VALUES
    ('REGISTER_SOURCE', '원천 등록',
     '관리자가 원천과 접속 계정을 등록한다(API 010). 원천 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'SOURCE_MANAGE'),
    ('UPDATE_SOURCE', '원천 정보 변경',
     '관리자가 원천 이름·접속 설정·접속 계정을 바꾼다(API 012). 원천 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'SOURCE_MANAGE'),
    ('CREATE_ACTION_DEFINITION', 'Action 정의 등록',
     '관리자가 업무 Action 정의를 등록한다(API 088). Action API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ACTION_DEFINITION_MANAGE'),
    ('UPDATE_ACTION_DEFINITION', 'Action 정의 수정',
     '관리자가 업무 Action 정의를 수정한다(API 090). Action API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ACTION_DEFINITION_MANAGE'),
    ('UPDATE_SYNC_SCHEDULE', '원천별 수집 주기 설정',
     '관리자가 원천의 자동 수집 여부와 주기를 바꾼다(API 072). 수집 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'SYNC_SCHEDULE_MANAGE'),
    ('DECIDE_PROPOSAL', '후보 승인·거절·보류',
     '관리자가 후보를 승인·거절·보류한다(API 054). 검토 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'PROPOSAL_DECIDE');
