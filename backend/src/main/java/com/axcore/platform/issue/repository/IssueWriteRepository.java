package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.domain.NewIssue;
import java.sql.Types;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** 문제 등록. 열린 문제 중복은 uq_issue_open 부분 유일 인덱스로 막는다. */
@Repository
@RequiredArgsConstructor
public class IssueWriteRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** 같은 키의 열린 문제가 없을 때만 넣는다. 이미 있으면 빈 값이다. */
    public Optional<Long> insertIfNoOpenIssue(NewIssue issue) {
        return jdbc.sql(
                        """
                        insert into issue (
                            issue_kind, dedupe_key, link_mapping_id, proposal_item_id,
                            object_instance_id, held_item_id, raw_value, transformed_value,
                            failure_reason, evidence, detected_check_run_id
                        )
                        values (
                            :kind, :dedupeKey, :linkMappingId, :proposalItemId,
                            :objectInstanceId, :heldItemId, :rawValue, :transformedValue,
                            :failureReason, cast(:evidence as jsonb), :checkRunId
                        )
                        on conflict (dedupe_key) where status <> 'RESOLVED' do nothing
                        returning id
                        """)
                .param("kind", issue.kind().name())
                .param("dedupeKey", issue.dedupeKey().value())
                .param("linkMappingId", issue.linkMappingId(), Types.BIGINT)
                .param("proposalItemId", issue.proposalItemId(), Types.BIGINT)
                .param("objectInstanceId", issue.objectInstanceId(), Types.BIGINT)
                .param("heldItemId", issue.heldItemId(), Types.BIGINT)
                .param("rawValue", issue.rawValue(), Types.VARCHAR)
                .param("transformedValue", issue.transformedValue(), Types.VARCHAR)
                .param("failureReason", issue.failureReason())
                .param("evidence", json.writeValueAsString(issue.evidence()))
                .param("checkRunId", issue.detectedCheckRunId())
                .query(Long.class)
                .optional();
    }

    public Optional<Long> findOpenIssueId(String dedupeKey) {
        return jdbc.sql(
                        "select id from issue where dedupe_key = :dedupeKey and status <> 'RESOLVED'")
                .param("dedupeKey", dedupeKey)
                .query(Long.class)
                .optional();
    }

    /** 검사가 문제를 처음 등록한 이력. 이전 상태와 처리자가 없다. */
    public void insertDetectedHistory(long issueId, long checkRunId) {
        jdbc.sql(
                        """
                        insert into issue_history (issue_id, from_status, to_status, check_run_id)
                        values (:issueId, null, 'NEW', :checkRunId)
                        """)
                .param("issueId", issueId)
                .param("checkRunId", checkRunId)
                .update();
    }
}
