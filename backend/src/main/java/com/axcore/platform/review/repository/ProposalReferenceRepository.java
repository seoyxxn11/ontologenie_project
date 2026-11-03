package com.axcore.platform.review.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ProposalReferenceRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public record ObjectType(Long id, String code) {}

    public record Relation(Long id, ObjectType source, ObjectType target) {}

    public Optional<Relation> relationById(long id) {
        return relation("lt.id = :reference", id);
    }

    public Optional<Relation> relationByCode(String code) {
        return relation("lt.code = :reference", code);
    }

    private Optional<Relation> relation(String predicate, Object reference) {
        return jdbc.sql(
                        """
                select lt.id relation_id, s.id source_id, s.code source_code, t.id target_id, t.code target_code
                from link_type lt join object_type s on s.id=lt.source_type_id
                join object_type t on t.id=lt.target_type_id where
                """
                                + predicate)
                .param("reference", reference)
                .query(
                        (rs, row) ->
                                new Relation(
                                        rs.getLong("relation_id"),
                                        new ObjectType(
                                                rs.getLong("source_id"),
                                                rs.getString("source_code")),
                                        new ObjectType(
                                                rs.getLong("target_id"),
                                                rs.getString("target_code"))))
                .optional();
    }

    public List<Map<String, Object>> pendingDefinitions(
            long analysisRunId, String kind, String code) {
        return jdbc.sql(
                        """
                select payload::text from proposal_item where analysis_run_id=:analysis
                    and item_kind=:kind and payload->>'code'=:code and review_status <> 'REJECTED'
                """)
                .param("analysis", analysisRunId)
                .param("kind", kind)
                .param("code", code)
                .query(
                        (rs, row) ->
                                json.readValue(
                                        rs.getString(1),
                                        new TypeReference<Map<String, Object>>() {}))
                .list();
    }

    public Optional<ObjectType> objectType(String code) {
        return jdbc.sql("select id, code from object_type where code=:code")
                .param("code", code)
                .query((rs, row) -> new ObjectType(rs.getLong("id"), rs.getString("code")))
                .optional();
    }

    public boolean propertyExists(ObjectType type, String propertyCode) {
        return type.id() != null
                && jdbc.sql(
                                """
                select exists(select 1 from property_definition where object_type_id=:id and code=:code)
                """)
                        .param("id", type.id())
                        .param("code", propertyCode)
                        .query(Boolean.class)
                        .single();
    }
}
