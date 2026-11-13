-- S15P31C106-394: 가리지 않은 민감(RESTRICTED) 값을 사람에게 내보낸 응답을 남기는 접속 기록 이벤트.
-- 값 원문은 남기지 않고 대상·필드 코드·경로·정책 버전만 result에 남긴다. ADMIN만 096에서 골라 본다.
ALTER TABLE action_execution DROP CONSTRAINT ck_action_execution_kind;
ALTER TABLE action_execution ADD CONSTRAINT ck_action_execution_kind CHECK (event_kind IN (
    'ACTION',
    'AGENT_QUERY',
    'SYNC_RUN',
    'CHECK_RUN',
    'ANALYSIS_RUN',
    'SOURCE_CHECK',
    'SOURCE_PREVIEW',
    'HOLD_AUTO_RESOLVE',
    'RESTRICTED_VIEW'));
