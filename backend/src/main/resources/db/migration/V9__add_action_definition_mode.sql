-- =====================================================================
-- V9: Action 정의를 업무 Action과 관리 Action으로 나눈다 (Jira S15P31C106-206, Action 정의서 2절, 2026-11-03·04 결정)
--   action_mode EXECUTE = 업무 Action. 091 실행 요청 → Handler로 실행하고 handler_key가 실행할 코드를 가리킨다.
--   action_mode RECORD  = 관리 Action. 각 도메인 API가 직접 실행하고, 정의는 감사 기록(action_execution)을 연결하려고 둔다.
--   V7이 handler_key='DIRECT'로 넣은 계정 관리 3행은 RECORD·handler_key 없음으로 바꾼다.
--   OBJECT Action의 대상 객체 유형은 선택으로 바꾼다. 재배정 계획처럼 초기 데이터로 넣는 Action은 객체 유형(관리자가 나중에 만듦)을
--   가리킬 수 없고, 입력 객체의 유형은 Handler가 검사한다(2026-11-04 결정).
-- =====================================================================

ALTER TABLE action_definition
    ADD COLUMN action_mode varchar(10),
    ALTER COLUMN handler_key DROP NOT NULL;
UPDATE action_definition SET action_mode = CASE WHEN handler_key = 'DIRECT' THEN 'RECORD' ELSE 'EXECUTE' END;
UPDATE action_definition SET handler_key = NULL WHERE action_mode = 'RECORD';

ALTER TABLE action_definition
    ALTER COLUMN action_mode SET NOT NULL,
    DROP CONSTRAINT ck_action_definition_target,
    ADD CONSTRAINT ck_action_definition_mode    CHECK (action_mode IN ('EXECUTE', 'RECORD')),
    ADD CONSTRAINT ck_action_definition_handler CHECK ((action_mode = 'EXECUTE') = (handler_key IS NOT NULL)),
    ADD CONSTRAINT ck_action_definition_target  CHECK (target_kind = 'OBJECT' OR target_object_type_id IS NULL);

COMMENT ON COLUMN action_definition.action_mode IS 'EXECUTE = 업무 Action(091 → Handler), RECORD = 관리 Action(도메인 API가 직접 실행, 감사 기록 연결용). 등록 뒤 바꾸지 않는다. RECORD 정의는 Flyway로만 넣는다';
COMMENT ON COLUMN action_definition.handler_key IS '091이 실행할 Handler 이름. EXECUTE일 때만 있다(ck_action_definition_handler)';
COMMENT ON COLUMN action_definition.target_object_type_id IS 'OBJECT Action이 받는 객체 유형. 비우면 유형을 정하지 않은 것이고 입력 객체의 유형은 Handler가 검사한다. SYSTEM이면 늘 NULL';
