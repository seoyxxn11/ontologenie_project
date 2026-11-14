-- =====================================================================
-- V24 Agent 대화 삭제를 숨기기로 바꾼다 (2026-11-13)
-- 대화를 행째 지우면 메시지가 함께 지워져야 하는데, Agent 조회 기록(action_execution.agent_message_id)과 재배정 계획
-- (reassignment_plan.agent_conversation_id)이 RESTRICT로 가리켜 대부분의 대화를 지울 수 없었다. 감사 기록을 남기기 위해
-- 행은 그대로 두고 지운 시각만 적는다. 지운 대화는 본인 목록·상세·메시지 API에서 없는 대화(404)로 답한다.
-- =====================================================================

ALTER TABLE agent_conversation
    ADD COLUMN deleted_at timestamptz;

COMMENT ON COLUMN agent_conversation.deleted_at IS '사용자가 대화를 지운 시각. NULL이면 지우지 않은 대화다. 지운 대화와 메시지는 감사 기록(action_execution·reassignment_plan)이 가리킬 수 있어 행을 지우지 않고 본인 화면에서만 숨긴다';
