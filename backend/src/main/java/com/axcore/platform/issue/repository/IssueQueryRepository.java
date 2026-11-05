package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueSearchRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 정합성 문제 읽기 전용 조회. */
@Repository
@RequiredArgsConstructor
public class IssueQueryRepository {

    private static final String SELECT_ISSUE =
            """
            select id, issue_kind, status, dedupe_key, failure_reason, raw_value,
                   transformed_value, evidence::text as evidence, object_instance_id,
                   link_mapping_id, proposal_item_id, held_item_id, detected_check_run_id,
                   resolved_check_run_id, forwarded_at, forwarded_by, created_at, row_version
            from issue
            """;

    private static final TypeReference<Map<String, Object>> EVIDENCE_TYPE =
            new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<IssueResponse> search(IssueSearchRequest request, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where true");
        Map<String, Object> parameters = new HashMap<>();
        if (request.status() != null) {
            where.append(" and status = :status");
            parameters.put("status", request.status());
        }
        if (request.issueKind() != null) {
            where.append(" and issue_kind = :issueKind");
            parameters.put("issueKind", request.issueKind());
        }
        if (request.linkMappingId() != null) {
            where.append(" and link_mapping_id = :linkMappingId");
            parameters.put("linkMappingId", request.linkMappingId());
        }
        if (request.from() != null) {
            where.append(" and created_at >= :from");
            parameters.put("from", Timestamp.from(request.from()));
        }
        if (request.to() != null) {
            where.append(" and created_at <= :to");
            parameters.put("to", Timestamp.from(request.to()));
        }

        long total =
                jdbc.sql("select count(*) from issue" + where)
                        .params(parameters)
                        .query(Long.class)
                        .single();
        // 최근에 등록된 문제부터 보여준다. 같은 시각이면 나중에 만든 문제가 먼저다.
        List<IssueResponse> content =
                jdbc.sql(
                                SELECT_ISSUE
                                        + where
                                        + " order by created_at desc, id desc"
                                        + " limit :size offset :offset")
                        .params(parameters)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query((rs, rowNum) -> toResponse(rs))
                        .list();
        return new PageImpl<>(content, pageable, total);
    }

    public Optional<IssueResponse> findById(long id) {
        return jdbc.sql(SELECT_ISSUE + " where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> toResponse(rs))
                .optional();
    }

    private IssueResponse toResponse(ResultSet rs) throws SQLException {
        return new IssueResponse(
                rs.getLong("id"),
                rs.getString("issue_kind"),
                rs.getString("status"),
                rs.getString("dedupe_key"),
                rs.getString("failure_reason"),
                rs.getString("raw_value"),
                rs.getString("transformed_value"),
                evidence(rs.getString("evidence")),
                rs.getObject("object_instance_id", Long.class),
                rs.getObject("link_mapping_id", Long.class),
                rs.getObject("proposal_item_id", Long.class),
                rs.getObject("held_item_id", Long.class),
                rs.getLong("detected_check_run_id"),
                rs.getObject("resolved_check_run_id", Long.class),
                instant(rs.getTimestamp("forwarded_at")),
                rs.getObject("forwarded_by", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("row_version"));
    }

    private Map<String, Object> evidence(String text) {
        Map<String, Object> evidence = new LinkedHashMap<>(json.readValue(text, EVIDENCE_TYPE));
        // 원인 확인 여부를 남기지 않은 근거는 원인 미확정으로 본다(FN-ISS-02).
        if (!(evidence.get("causeConfirmed") instanceof Boolean)) {
            evidence.put("causeConfirmed", false);
        }
        return evidence;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
