-- API 058은 문제 상세에서 직접 실행하는 관리 Action이다. 감사 기록 연결에 쓴다.
INSERT INTO permission (code, name, description) VALUES
    ('ISSUE_MANUAL_LINK', '미연결 문제 수동 연결', '미연결 문제에서 도착 객체를 직접 연결 (API 058)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code = 'ISSUE_MANUAL_LINK'
WHERE r.code = 'ADMIN';

INSERT INTO action_definition (
    code, name, description, target_kind, action_mode, action_kind, requires_confirmation, required_permission
) VALUES (
    'CREATE_ISSUE_MANUAL_LINK', '미연결 문제 수동 연결',
    '관리자가 미연결 문제 상세에서 도착 객체와 연결한다(API 058). 091로는 실행하지 않는다.',
    'SYSTEM', 'RECORD', 'WRITE', false, 'ISSUE_MANUAL_LINK'
);
