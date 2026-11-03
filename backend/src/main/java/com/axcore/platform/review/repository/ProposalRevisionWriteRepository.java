package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.ProposalOptionCreateRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ProposalRevisionWriteRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public void insert(
            ProposalItem item, List<ProposalOptionCreateRequest> options, long accountId) {
        jdbc.sql(
                        """
                insert into proposal_revision
                    (proposal_item_id, revision, payload, evidence, ai_confidence, created_by)
                values (:id, :revision, cast(:payload as jsonb), cast(:evidence as jsonb), null, :account)
                """)
                .param("id", item.getId())
                .param("revision", item.getRevision())
                .param("payload", json.writeValueAsString(item.getPayload()))
                .param("evidence", json.writeValueAsString(item.getEvidence()))
                .param("account", accountId)
                .update();
        for (int i = 0; i < options.size(); i++) {
            ProposalOptionCreateRequest option = options.get(i);
            jdbc.sql(
                            """
                    insert into proposal_option
                        (proposal_item_id, revision, option_no, payload, evidence, ai_confidence)
                    values (:id, :revision, :no, cast(:payload as jsonb), cast(:evidence as jsonb), null)
                    """)
                    .param("id", item.getId())
                    .param("revision", item.getRevision())
                    .param("no", i + 1)
                    .param("payload", json.writeValueAsString(option.payload()))
                    .param("evidence", json.writeValueAsString(option.evidence()))
                    .update();
        }
    }
}
