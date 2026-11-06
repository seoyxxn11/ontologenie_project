package com.axcore.platform.review.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Decision audit snapshots use the actual flushed database values, including nullable fields. */
@Repository
@RequiredArgsConstructor
public class ProposalDecisionAuditRepository {
    private final JdbcClient jdbc;

    public Map<String, Object> proposal(long id) {
        return jdbc.sql(
                        """
                select id, revision, review_status, apply_status, applied_at, apply_error,
                    row_version, updated_by, updated_at
                from proposal_item where id=:id
                """)
                .param("id", id)
                .query((rs, row) -> snapshot(rs))
                .single();
    }

    public Map<String, Object> decision(long id) {
        return jdbc.sql(
                        """
                select id, proposal_item_id, item_revision, decision, selected_option_id,
                    reason, decided_by, decided_at
                from review_decision where id=:id
                """)
                .param("id", id)
                .query((rs, row) -> snapshot(rs))
                .single();
    }

    private static Map<String, Object> snapshot(ResultSet rs) throws SQLException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        var columns = rs.getMetaData();
        for (int index = 1; index <= columns.getColumnCount(); index++) {
            Object value = rs.getObject(index);
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
            else if (value instanceof OffsetDateTime timestamp) value = timestamp.toInstant();
            snapshot.put(columns.getColumnLabel(index), value);
        }
        return snapshot;
    }
}
