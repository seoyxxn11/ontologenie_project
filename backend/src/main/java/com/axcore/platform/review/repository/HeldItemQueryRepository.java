package com.axcore.platform.review.repository;

import com.axcore.platform.review.dto.HeldItemResponse;
import com.axcore.platform.review.dto.HeldItemSearchRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class HeldItemQueryRepository {
    private static final String SELECT_ITEM =
            """
            select id, proposal_item_id, link_mapping_id, source_object_id, link_type_id,
                   status, raw_value, transformed_value, hold_reason, resolved_link_id,
                   resolved_sync_run_id, resolved_by, resolved_at, resolution_note, created_at
            from held_item
            """;

    private final JdbcClient jdbc;

    public Page<HeldItemResponse> search(HeldItemSearchRequest request, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where 1=1");
        Map<String, Object> parameters = new HashMap<>();
        if (request.status() != null) {
            where.append(" and status = :status");
            parameters.put("status", request.status());
        }
        if (request.linkTypeId() != null) {
            where.append(" and link_type_id = :linkTypeId");
            parameters.put("linkTypeId", request.linkTypeId());
        }

        long total =
                jdbc.sql("select count(*) from held_item" + where)
                        .params(parameters)
                        .query(Long.class)
                        .single();
        var content =
                jdbc.sql(SELECT_ITEM + where + " order by id desc limit :size offset :offset")
                        .params(parameters)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query((rs, rowNum) -> toResponse(rs))
                        .list();
        return new PageImpl<>(content, pageable, total);
    }

    public Optional<HeldItemResponse> findById(long id) {
        return jdbc.sql(SELECT_ITEM + " where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> toResponse(rs))
                .optional();
    }

    private HeldItemResponse toResponse(ResultSet rs) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new HeldItemResponse(
                rs.getLong("id"),
                rs.getObject("proposal_item_id", Long.class),
                rs.getObject("link_mapping_id", Long.class),
                rs.getLong("source_object_id"),
                rs.getLong("link_type_id"),
                rs.getString("status"),
                rs.getString("raw_value"),
                rs.getString("transformed_value"),
                rs.getString("hold_reason"),
                rs.getObject("resolved_link_id", Long.class),
                rs.getObject("resolved_sync_run_id", Long.class),
                rs.getObject("resolved_by", Long.class),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rs.getString("resolution_note"),
                rs.getTimestamp("created_at").toInstant());
    }
}
