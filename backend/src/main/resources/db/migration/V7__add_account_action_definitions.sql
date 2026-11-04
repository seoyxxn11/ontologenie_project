-- =====================================================================
-- V7: 계정 관리 Action 초기 데이터 (Jira S15P31C106-203)
--   Action 정의서 3-2·5절의 계정 관리 3개만 넣는다. 나머지 관리 Action·업무 Action과
--   권한 코드는 S15P31C106-206에서 넣는다.
--   관리 Action은 각 도메인 API가 직접 실행한다. 이 정의는 감사 기록(action_execution)을
--   연결하려고 둔다(ck_action_execution_action).
-- =====================================================================

INSERT INTO permission (code, name, description) VALUES
    ('ACCOUNT_MANAGE', '계정 관리', '계정 생성·변경·비밀번호 초기화 (API 005·007·103)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code = 'ACCOUNT_MANAGE'
WHERE r.code = 'ADMIN';

INSERT INTO action_definition (
    code, name, description, target_kind, handler_key, action_kind,
    requires_confirmation, required_permission
) VALUES
    ('CREATE_ACCOUNT', '계정 생성',
     '관리자가 새 계정을 만든다(API 005). 계정 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'DIRECT', 'WRITE', false, 'ACCOUNT_MANAGE'),
    ('UPDATE_ACCOUNT', '계정·역할 변경',
     '관리자가 계정의 이름·역할·상태를 바꾼다(API 007). 계정 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'DIRECT', 'WRITE', false, 'ACCOUNT_MANAGE'),
    ('RESET_ACCOUNT_PASSWORD', '관리자 비밀번호 초기화',
     '관리자가 계정의 비밀번호를 임시 비밀번호로 바꾼다(API 103). 계정 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'DIRECT', 'WRITE', false, 'ACCOUNT_MANAGE');
