-- =====================================================================
-- V8: Agent 답변의 처리 중 상태 (Jira S15P31C106-141, 2026-11-03 결정)
--   085는 질문을 202로 접수하고 처리 중(RUNNING)인 AGENT 메시지를 먼저 저장한다.
--   화면은 084를 반복 조회해 단계와 결과를 확인한다. 처리 단계는 answer_meta.stage에 둔다(컬럼 추가 없음).
--   docs/agent-response-policy.md 3장
-- =====================================================================

ALTER TABLE agent_message DROP CONSTRAINT ck_agent_message_status;
ALTER TABLE agent_message
    ADD CONSTRAINT ck_agent_message_status CHECK (
        (sender = 'AGENT') = (status IS NOT NULL)
        AND (status IS NULL OR status IN ('RUNNING', 'SUCCEEDED', 'FAILED')));

-- 대화마다 처리 중인 답변은 하나만. 서버가 여러 대여도 같은 대화의 동시 질문을 막는다(409)
CREATE UNIQUE INDEX uq_agent_message_running ON agent_message (conversation_id) WHERE status = 'RUNNING';

COMMENT ON COLUMN agent_message.status IS 'AGENT: RUNNING(처리 중) / SUCCEEDED / FAILED(LLM 호출 실패·시간 초과 등 답변 자체 실패). USER면 NULL';
COMMENT ON COLUMN agent_message.answer_meta IS 'AGENT: RUNNING이면 {stage}, 끝나면 정보별 결과(results[])·동기화 상태(syncStatus)·출처·기준 시점';