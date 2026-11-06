package com.axcore.platform.review;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class DecisionHistoryFixtures {
    private final JdbcClient jdbc;

    public DecisionHistoryFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long account() {
        return jdbc.sql(
                        """
                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                values (:login, '결정 이력 테스트', 'test-hash',
                    (select id from role where code='ADMIN'), false) returning id
                """)
                .param("login", "decision-history-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    public long proposal(long account) {
        return proposal(account, "{}");
    }

    public long proposal(long account, String payload) {
        return jdbc.sql(
                        """
                with candidate as (
                    insert into proposal_item(origin, item_kind, payload, created_by)
                    values ('MANUAL', 'LINK_RULE', cast(:payload as jsonb), :account) returning *
                )
                insert into proposal_revision(proposal_item_id, revision, payload, evidence, created_by)
                select id, revision, payload, evidence, created_by from candidate returning proposal_item_id
                """)
                .param("account", account)
                .param("payload", payload)
                .query(Long.class)
                .single();
    }

    public void revise(long proposal, int revision) {
        jdbc.sql(
                        """
                insert into proposal_revision(proposal_item_id, revision, payload, evidence, created_by)
                select id, :revision, payload, evidence, created_by from proposal_item where id=:id
                """)
                .param("id", proposal)
                .param("revision", revision)
                .update();
        jdbc.sql("update proposal_item set revision=:revision where id=:id")
                .param("id", proposal)
                .param("revision", revision)
                .update();
    }

    public long option(long proposal, int revision) {
        return option(proposal, revision, "{}");
    }

    public long option(long proposal, int revision, String payload) {
        return jdbc.sql(
                        """
                insert into proposal_option(proposal_item_id, revision, option_no, payload)
                values (:id, :revision, 1, cast(:payload as jsonb)) returning id
                """)
                .param("payload", payload)
                .param("id", proposal)
                .param("revision", revision)
                .query(Long.class)
                .single();
    }

    public void successfulCheck(long proposal, int revision, long account) {
        long run =
                jdbc.sql(
                                """
                insert into check_run(trigger_kind, status, scope, work_key, requested_by,
                    started_at, finished_at)
                values ('CANDIDATE', 'SUCCEEDED',
                    jsonb_build_object('proposalItems', jsonb_build_array(jsonb_build_object(
                        'proposalItemId', cast(:id as bigint), 'revision', cast(:revision as integer)))),
                    :key, :account, now(), now()) returning id
                """)
                        .param("id", proposal)
                        .param("revision", revision)
                        .param("key", "decision-history-check-" + UUID.randomUUID())
                        .param("account", account)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into check_result(check_run_id, proposal_item_id, proposal_item_revision,
                    total_count, matched_count, unmatched_count, duplicate_count, violation_count)
                values (:run, :id, :revision, 0, 0, 0, 0, 0)
                """)
                .param("run", run)
                .param("id", proposal)
                .param("revision", revision)
                .update();
    }

    public long decision(
            long proposal,
            int revision,
            String decision,
            Long option,
            String reason,
            long account,
            String decidedAt) {
        return jdbc.sql(
                        """
                insert into review_decision(proposal_item_id, item_revision, decision,
                    selected_option_id, reason, decided_by, decided_at)
                values (:id, :revision, :decision, :option, :reason, :account,
                    cast(:at as timestamptz)) returning id
                """)
                .param("id", proposal)
                .param("revision", revision)
                .param("decision", decision)
                .param("option", option, java.sql.Types.BIGINT)
                .param("reason", reason)
                .param("account", account)
                .param("at", decidedAt)
                .query(Long.class)
                .single();
    }

    public long count(long proposal) {
        return jdbc.sql("select count(*) from review_decision where proposal_item_id=:id")
                .param("id", proposal)
                .query(Long.class)
                .single();
    }
}
