package com.axcore.platform.review.repository;

import com.axcore.platform.review.dto.ProposalDecisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProposalDecisionQueryRepository {
    private final JdbcClient jdbc;

    public Page<ProposalDecisionResponse> findByProposalItemId(
            long proposalItemId, Pageable pageable) {
        long total =
                jdbc.sql(
                                """
                select count(*) from review_decision where proposal_item_id=:id
                """)
                        .param("id", proposalItemId)
                        .query(Long.class)
                        .single();
        var content =
                jdbc.sql(
                                """
                select id, proposal_item_id, item_revision, decision, selected_option_id,
                       reason, decided_by, decided_at
                from review_decision
                where proposal_item_id=:id
                order by decided_at desc, id desc
                limit :size offset :offset
                """)
                        .param("id", proposalItemId)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query(
                                (rs, row) ->
                                        new ProposalDecisionResponse(
                                                rs.getLong("id"),
                                                rs.getLong("proposal_item_id"),
                                                rs.getInt("item_revision"),
                                                rs.getString("decision"),
                                                rs.getObject("selected_option_id", Long.class),
                                                rs.getString("reason"),
                                                rs.getLong("decided_by"),
                                                rs.getTimestamp("decided_at").toInstant()))
                        .list();
        return new PageImpl<>(content, pageable, total);
    }
}
