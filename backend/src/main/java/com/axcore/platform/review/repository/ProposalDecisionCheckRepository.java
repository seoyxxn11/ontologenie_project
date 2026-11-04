package com.axcore.platform.review.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProposalDecisionCheckRepository {
    private final JdbcClient jdbc;

    public boolean hasSuccessfulCheck(long proposalId, int revision) {
        return jdbc.sql(
                        """
                select exists (
                    select 1
                    from check_run run
                    join check_result result on result.check_run_id = run.id
                    where run.trigger_kind = 'CANDIDATE'
                        and run.status = 'SUCCEEDED'
                        and result.proposal_item_id = :proposalId
                        and result.proposal_item_revision = :revision
                        and run.scope @> jsonb_build_object(
                            'proposalItems', jsonb_build_array(jsonb_build_object(
                                'proposalItemId', cast(:proposalId as bigint),
                                'revision', cast(:revision as integer))))
                )
                """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .query(Boolean.class)
                .single();
    }

    public Optional<CheckRunStatus> findLatestCheck(long proposalId, int revision) {
        return jdbc.sql(
                        """
                select id, status
                from check_run
                where trigger_kind = 'CANDIDATE'
                    and scope @> jsonb_build_object(
                        'proposalItems', jsonb_build_array(jsonb_build_object(
                            'proposalItemId', cast(:proposalId as bigint),
                            'revision', cast(:revision as integer))))
                order by requested_at desc, id desc
                limit 1
                """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .query((rs, row) -> new CheckRunStatus(rs.getLong("id"), rs.getString("status")))
                .optional();
    }

    public record CheckRunStatus(long checkRunId, String status) {}
}
