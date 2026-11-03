package com.axcore.platform.review.repository;

import com.axcore.platform.review.dto.ProposalRevisionResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ProposalRevisionQueryRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<ProposalRevisionResponse> findByProposalItemId(
            long proposalItemId, Pageable pageable) {
        long total =
                jdbc.sql(
                                """
                                select count(*)
                                from proposal_revision
                                where proposal_item_id = :proposalItemId
                                """)
                        .param("proposalItemId", proposalItemId)
                        .query(Long.class)
                        .single();

        var content =
                jdbc.sql(
                                """
                                select revision,
                                       payload::text as payload,
                                       evidence::text as evidence,
                                       ai_confidence,
                                       created_by,
                                       created_at
                                from proposal_revision
                                where proposal_item_id = :proposalItemId
                                order by revision asc
                                limit :size offset :offset
                                """)
                        .param("proposalItemId", proposalItemId)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query(
                                (rs, rowNum) ->
                                        new ProposalRevisionResponse(
                                                rs.getInt("revision"),
                                                readMap(rs.getString("payload")),
                                                readMap(rs.getString("evidence")),
                                                rs.getBigDecimal("ai_confidence"),
                                                rs.getObject("created_by", Long.class),
                                                rs.getTimestamp("created_at").toInstant()))
                        .list();

        return new PageImpl<>(content, pageable, total);
    }

    private Map<String, Object> readMap(String value) {
        return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    }
}
