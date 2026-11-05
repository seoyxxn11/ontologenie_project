package com.axcore.platform.action.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Action 정의·실행이 가리키는 다른 표(permission, role_permission, object_type, agent_message)를 확인한다. */
@Repository
@RequiredArgsConstructor
public class ActionReferenceRepository {

    private final JdbcClient jdbc;

    public boolean permissionExists(String code) {
        return jdbc.sql("select exists (select 1 from permission where code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    public boolean roleHasPermission(String roleCode, String permissionCode) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1 from role_permission rp
                            join role r on r.id = rp.role_id
                            join permission p on p.id = rp.permission_id
                            where r.code = :roleCode and p.code = :permissionCode)
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .query(Boolean.class)
                .single();
    }

    /** Agent 실행의 agentMessageId가 실행자 본인 대화의 질문(USER) 메시지인가. */
    public boolean isQuestionOf(long agentMessageId, long accountId) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1 from agent_message m
                            join agent_conversation c on c.id = m.conversation_id
                            where m.id = :messageId and m.sender = 'USER'
                              and c.account_id = :accountId)
                        """)
                .param("messageId", agentMessageId)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single();
    }

    public boolean objectTypeExists(long id) {
        return jdbc.sql("select exists (select 1 from object_type where id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }
}
