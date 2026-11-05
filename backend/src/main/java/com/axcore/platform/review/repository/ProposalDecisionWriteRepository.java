package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProposalDecisionWriteRepository {
    private final JdbcClient jdbc;

    public long insert(
            ProposalItem item, ProposalDecisionRequest request, long decidedBy, Instant decidedAt) {
        return jdbc.sql(
                        """
                insert into review_decision(proposal_item_id, item_revision, decision,
                    selected_option_id, reason, decided_by, decided_at)
                values (:id, :revision, :decision, :option, :reason, :account, :at)
                returning id
                """)
                .param("id", item.getId())
                .param("revision", item.getRevision())
                .param("decision", request.decision())
                .param("option", request.selectedOptionId(), Types.BIGINT)
                .param("reason", request.reason())
                .param("account", decidedBy)
                .param("at", decidedAt.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }
}
