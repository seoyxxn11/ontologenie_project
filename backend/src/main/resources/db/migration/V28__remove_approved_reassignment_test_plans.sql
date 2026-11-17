-- 2026-11-17 승인한 테스트 계획 6건만 정리한다. ID와 생성 시각·연결 관계가 모두 맞아야 한다.
-- 대화는 기존 삭제 방식(deleted_at)으로 숨기며 메시지·실행·변경 감사 기록은 보존한다.
-- Flyway의 단일 트랜잭션에서 실행한다. 대상 내용이 바뀌었으면 전체 정리를 중단한다.
DO $$
DECLARE
    target record;
    matched_plan_id bigint;
    actual_operations text[];
    message_count bigint;
    last_message_id bigint;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            (1::bigint, '2026-11-12 16:06:56.669357+09'::timestamptz, 48::bigint, 758::bigint, 10::bigint, 278::bigint, ARRAY['20', '30']),
            (2, '2026-11-14 19:38:03.886940+09'::timestamptz, 96, 1615, 2, 464, ARRAY['20', '30']),
            (3, '2026-11-14 23:34:52.782254+09'::timestamptz, 102, 1665, 8, 482, ARRAY['*']),
            (4, '2026-11-14 23:36:10.396638+09'::timestamptz, 103, 1697, 8, 490, ARRAY['20', '30']),
            (5, '2026-11-15 19:20:32.942815+09'::timestamptz, 133, 1987, 8, 574, ARRAY['20', '30']),
            (6, '2026-11-17 11:34:38.934029+09'::timestamptz, 151, 2172, 8, 640, ARRAY['20', '30'])
        ) AS approved(plan_id, created_at, conversation_id, execution_id, message_count, last_message_id, operations)
        ORDER BY plan_id
    LOOP
        SELECT p.id INTO matched_plan_id
        FROM reassignment_plan p
        WHERE p.id = target.plan_id AND p.created_at = target.created_at
          AND p.agent_conversation_id = target.conversation_id
          AND p.action_execution_id = target.execution_id
          AND p.created_by = 1 AND p.from_equipment_id = 3 AND p.status = 'CREATED'
        FOR UPDATE;

        -- 빈 DB나 다른 환경에서 같은 ID를 쓰더라도 승인 대상과 일치하지 않으면 변경하지 않는다.
        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        PERFORM 1 FROM agent_conversation
        WHERE id = target.conversation_id AND account_id = 1
        FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Approved cleanup conversation changed for plan %', target.plan_id;
        END IF;

        PERFORM 1 FROM reassignment_plan_item WHERE plan_id = target.plan_id FOR UPDATE;
        SELECT array_agg(coalesce(operation_code, '*') ORDER BY operation_code NULLS FIRST)
        INTO actual_operations
        FROM reassignment_plan_item
        WHERE plan_id = target.plan_id;
        IF actual_operations IS DISTINCT FROM target.operations OR EXISTS (
            SELECT 1 FROM reassignment_plan_item
            WHERE plan_id = target.plan_id
              AND (work_order_id <> 731 OR from_equipment_id <> 3 OR to_equipment_id <> 2)
        ) THEN
            RAISE EXCEPTION 'Approved cleanup items changed for plan %', target.plan_id;
        END IF;

        -- 대화 잠금은 새 메시지·계획의 FK 참조와 경합한다. 기존 답변의 상태 변경도 잠근다.
        PERFORM 1 FROM agent_message WHERE conversation_id = target.conversation_id FOR UPDATE;
        SELECT count(*), max(id) INTO message_count, last_message_id
        FROM agent_message WHERE conversation_id = target.conversation_id;
        IF message_count <> target.message_count OR last_message_id IS DISTINCT FROM target.last_message_id
            OR EXISTS (SELECT 1 FROM agent_message
                       WHERE conversation_id = target.conversation_id AND status = 'RUNNING')
            OR EXISTS (SELECT 1 FROM reassignment_plan
                       WHERE agent_conversation_id = target.conversation_id AND id <> target.plan_id)
        THEN
            RAISE EXCEPTION 'Approved cleanup conversation has new work for plan %', target.plan_id;
        END IF;

        UPDATE agent_conversation
        SET deleted_at = coalesce(deleted_at, statement_timestamp())
        WHERE id = target.conversation_id;
        DELETE FROM reassignment_plan WHERE id = matched_plan_id;
        -- 항목은 FK의 ON DELETE CASCADE로만 삭제한다. 실행·감사 기록은 건드리지 않는다.
    END LOOP;
END $$;
