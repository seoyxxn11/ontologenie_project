package com.axcore.platform.review.repository;

import com.axcore.platform.review.dto.AiProposalRequest;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class AiProposalWriteRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public long insert(long analysisRunId, AiProposalRequest proposal, Instant now) {
        String payload = json.writeValueAsString(proposal.payload());
        String evidence = json.writeValueAsString(proposal.evidence());
        long id =
                jdbc.sql(
                                """
                insert into proposal_item(analysis_run_id, origin, item_kind, revision, payload, evidence,
                    ai_confidence, review_status, apply_status, created_at, updated_at)
                values (:analysis, 'AI', :kind, 1, cast(:payload as jsonb), cast(:evidence as jsonb),
                    :confidence, 'PENDING', 'NOT_APPLIED', :now, :now) returning id
                """)
                        .param("analysis", analysisRunId)
                        .param("kind", proposal.itemKind())
                        .param("payload", payload)
                        .param("evidence", evidence)
                        .param("confidence", proposal.aiConfidence(), Types.NUMERIC)
                        .param("now", Timestamp.from(now))
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into proposal_revision(proposal_item_id, revision, payload, evidence, ai_confidence, created_at)
                values (:id, 1, cast(:payload as jsonb), cast(:evidence as jsonb), :confidence, :now)
                """)
                .param("id", id)
                .param("payload", payload)
                .param("evidence", evidence)
                .param("confidence", proposal.aiConfidence(), Types.NUMERIC)
                .param("now", Timestamp.from(now))
                .update();
        for (var option : proposal.options()) {
            jdbc.sql(
                            """
                    insert into proposal_option(proposal_item_id, revision, option_no, payload, evidence, ai_confidence)
                    values (:id, 1, :no, cast(:payload as jsonb), cast(:evidence as jsonb), :confidence)
                    """)
                    .param("id", id)
                    .param("no", option.optionNo())
                    .param("payload", json.writeValueAsString(option.payload()))
                    .param("evidence", json.writeValueAsString(option.evidence()))
                    .param("confidence", option.aiConfidence(), Types.NUMERIC)
                    .update();
        }
        return id;
    }
}
