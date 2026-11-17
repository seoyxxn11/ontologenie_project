package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 승인된 데이터만 지우는 SQL을 합성 데이터가 있는 PostgreSQL에서 실행한다. 운영 DB에는 연결하지 않는다. */
class ReassignmentTestDataCleanupMigrationTests {
    private Connection connection;
    private JdbcClient jdbc;
    private String cleanup;

    @BeforeEach
    void createIsolatedFixture() throws Exception {
        var database = new TestcontainersConfiguration().testDatabase();
        connection =
                DriverManager.getConnection(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        jdbc.sql("set search_path = platform").update();
        cleanup =
                new ClassPathResource(
                                "db/migration/V28__remove_approved_reassignment_test_plans.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        jdbc.sql(
                        """
                        insert into account (id, login_id, display_name, password_hash, role_id)
                        values (1, 'cleanup-fixture', '합성 테스트 사용자', 'fixture-hash',
                                (select id from role where code = 'PLANNER'));
                        insert into object_type (id, code, name) values (1, 'CLEANUP_FIXTURE', '합성 객체');
                        insert into object_instance (id, object_type_id, display_name)
                        values (2, 1, '대체 설비'), (3, 1, '정지 설비'), (731, 1, '작업지시');
                        insert into agent_conversation (id, account_id, deleted_at)
                        select id, 1, case when id = 96 then null else '2026-11-17T07:00:00Z'::timestamptz end
                        from unnest(array[48, 96, 102, 103, 133, 151]) id;
                        insert into agent_message (id, conversation_id, sender, content, status)
                        select message_id, conversation_id,
                               case when message_id % 2 = 0 then 'AGENT' else 'USER' end,
                               '합성 테스트 메시지',
                               case when message_id % 2 = 0 then 'SUCCEEDED' else null end
                        from (values (48, 269, 278), (96, 463, 464), (102, 475, 482),
                                     (103, 483, 490), (133, 567, 574), (151, 633, 640))
                             fixture(conversation_id, first_id, last_id),
                             lateral generate_series(first_id, last_id) message_id;
                        insert into action_execution
                            (id, event_kind, action_definition_id, actor_type, actor_id, entry_point,
                             status, finished_at, agent_message_id, result)
                        select execution_id, 'ACTION',
                               (select id from action_definition where code = 'CREATE_REASSIGNMENT_PLAN'),
                               'AGENT', 1, 'AGENT', 'SUCCEEDED', now(), message_id,
                               jsonb_build_object('planId', plan_id)
                        from (values (1, 758, 278), (2, 1615, 464), (3, 1665, 482),
                                     (4, 1697, 490), (5, 1987, 574), (6, 2172, 640))
                             fixture(plan_id, execution_id, message_id);
                        insert into reassignment_plan
                            (id, from_equipment_id, data_as_of, action_execution_id,
                             agent_conversation_id, created_by, created_at)
                        select plan_id, 3, now(), execution_id, conversation_id, 1, created_at::timestamptz
                        from (values
                            (1, 758, 48, '2026-11-12T16:06:56.669357+09:00'),
                            (2, 1615, 96, '2026-11-14T19:38:03.886940+09:00'),
                            (3, 1665, 102, '2026-11-14T23:34:52.782254+09:00'),
                            (4, 1697, 103, '2026-11-14T23:36:10.396638+09:00'),
                            (5, 1987, 133, '2026-11-15T19:20:32.942815+09:00'),
                            (6, 2172, 151, '2026-11-17T11:34:38.934029+09:00'))
                             fixture(plan_id, execution_id, conversation_id, created_at);
                        insert into reassignment_plan_item
                            (plan_id, work_order_id, operation_code, from_equipment_id, to_equipment_id)
                        select p.id, 731, operation_code, 3, 2
                        from reassignment_plan p cross join (values ('20'), ('30')) op(operation_code)
                        where p.id <> 3;
                        insert into reassignment_plan_item
                            (plan_id, work_order_id, from_equipment_id, to_equipment_id)
                        values (3, 731, 3, 2);
                        insert into entity_change
                            (action_execution_id, table_name, row_key, change_kind, after_data)
                        select action_execution_id, 'reassignment_plan', jsonb_build_object('id', id),
                               'INSERT', to_jsonb(p) from reassignment_plan p;
                        insert into entity_change
                            (action_execution_id, table_name, row_key, change_kind, after_data)
                        select p.action_execution_id, 'reassignment_plan_item', jsonb_build_object('id', i.id),
                               'INSERT', to_jsonb(i)
                        from reassignment_plan_item i join reassignment_plan p on p.id = i.plan_id;
                        """)
                .update();
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void removesSixApprovedPlansAndOnlyHidesTheirConversations() {
        addUnrelatedPlan(200);
        Map<String, String> preserved =
                snapshots("agent_message", "action_execution", "entity_change", "object_instance");
        String alreadyDeleted =
                jdbc.sql("select deleted_at::text from agent_conversation where id = 48")
                        .query(String.class)
                        .single();

        jdbc.sql(cleanup).update();

        assertThat(ids("reassignment_plan")).isEqualTo("100");
        assertThat(count("reassignment_plan_item")).isZero();
        assertThat(
                        jdbc.sql(
                                        "select count(*) from agent_conversation where id <> 200 and deleted_at is not null")
                                .query(Long.class)
                                .single())
                .isEqualTo(6);
        assertThat(
                        jdbc.sql("select deleted_at::text from agent_conversation where id = 48")
                                .query(String.class)
                                .single())
                .isEqualTo(alreadyDeleted);
        assertThat(
                        jdbc.sql("select deleted_at is null from agent_conversation where id = 200")
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(snapshots(preserved.keySet().toArray(String[]::new))).isEqualTo(preserved);

        Map<String, String> firstRun = snapshots("reassignment_plan", "agent_conversation");
        jdbc.sql(cleanup).update();
        assertThat(snapshots("reassignment_plan", "agent_conversation")).isEqualTo(firstRun);
    }

    @Test
    void leavesSameIdsWithDifferentCreationTimeUntouched() {
        jdbc.sql("update reassignment_plan set created_at = created_at + interval '1 second'")
                .update();
        Map<String, String> before = allSnapshots();

        jdbc.sql(cleanup).update();

        assertThat(allSnapshots()).isEqualTo(before);
    }

    @Test
    void doesNotHideConversationWhosePlanIsAlreadyMissing() {
        jdbc.sql("delete from reassignment_plan where id = 2").update();

        jdbc.sql(cleanup).update();

        assertThat(count("reassignment_plan")).isZero();
        assertThat(
                        jdbc.sql("select deleted_at is null from agent_conversation where id = 96")
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void changedItemsAbortAndRollBackEarlierDeletions() {
        jdbc.sql("update reassignment_plan_item set to_equipment_id = 731 where plan_id = 6")
                .update();
        assertCleanupFailsWithoutChanges("Approved cleanup items changed for plan 6");
    }

    @Test
    void newMessagesAbortAndRollBackEarlierDeletions() {
        jdbc.sql(
                        "insert into agent_message (id, conversation_id, sender, content) values (641, 151, 'USER', '새 작업')")
                .update();
        assertCleanupFailsWithoutChanges("Approved cleanup conversation has new work for plan 6");
    }

    @Test
    void runningAnswerAbortsCleanup() {
        jdbc.sql("update agent_message set status = 'RUNNING' where id = 640").update();
        assertCleanupFailsWithoutChanges("Approved cleanup conversation has new work for plan 6");
    }

    @Test
    void anotherPlanInTheConversationAbortsCleanup() {
        addUnrelatedPlan(151);
        assertCleanupFailsWithoutChanges("Approved cleanup conversation has new work for plan 6");
    }

    private void assertCleanupFailsWithoutChanges(String message) {
        Map<String, String> before = allSnapshots();
        assertThatThrownBy(() -> jdbc.sql(cleanup).update()).hasMessageContaining(message);
        assertThat(allSnapshots()).isEqualTo(before);
    }

    private void addUnrelatedPlan(long conversationId) {
        jdbc.sql(
                        "insert into agent_conversation (id, account_id) values (:id, 1) on conflict do nothing")
                .param("id", conversationId)
                .update();
        jdbc.sql(
                        """
                        insert into action_execution
                            (id, event_kind, action_definition_id, actor_type, actor_id, entry_point,
                             status, finished_at)
                        values (3000, 'ACTION',
                                (select id from action_definition where code = 'CREATE_REASSIGNMENT_PLAN'),
                                'USER', 1, 'WEB', 'SUCCEEDED', now())
                        """)
                .update();
        jdbc.sql(
                        """
                        insert into reassignment_plan
                            (id, from_equipment_id, data_as_of, action_execution_id,
                             agent_conversation_id, created_by)
                        values (100, 3, now(), 3000, :conversation, 1)
                        """)
                .param("conversation", conversationId)
                .update();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private String ids(String table) {
        return jdbc.sql("select string_agg(id::text, ',' order by id) from " + table)
                .query(String.class)
                .single();
    }

    private Map<String, String> allSnapshots() {
        return snapshots(
                "reassignment_plan",
                "reassignment_plan_item",
                "agent_conversation",
                "agent_message",
                "action_execution",
                "entity_change",
                "object_instance");
    }

    private Map<String, String> snapshots(String... tables) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : tables) {
            result.put(
                    table,
                    jdbc.sql(
                                    "select coalesce(jsonb_agg(to_jsonb(t) order by id), '[]'::jsonb)::text from "
                                            + table
                                            + " t")
                            .query(String.class)
                            .single());
        }
        return result;
    }
}
