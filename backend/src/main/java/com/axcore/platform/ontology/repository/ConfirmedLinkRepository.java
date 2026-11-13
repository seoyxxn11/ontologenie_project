package com.axcore.platform.ontology.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자가 확정한 연결(보류 건 승인 MANUAL·문서 연결 DOCUMENT)의 읽기·쓰기(Jira 329).
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): object_instance·object_link는 아직 Entity가
 * 없는 테이블이라 규칙 연결({@link RuleLinkRepository})과 같은 방식을 따른다. 양 끝 객체의 공유 잠금({@code for share})도 SQL로만
 * 잡는다. 같은 트랜잭션에서 이 표들을 Entity로 다루는 코드가 없으므로 flush·clear는 필요 없다.
 */
@Repository
@RequiredArgsConstructor
public class ConfirmedLinkRepository {

    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /**
     * 객체들을 ID 순서로 공유 잠금하며 읽는다. 잠금을 잡는 동안 수집이 그 객체를 원천 삭제로 바꾸지 못하고, 이미 바꾸는 중이면 그 커밋을 기다린 뒤 바뀐 상태를
     * 읽는다. 없는 ID는 빠진다. 연결 대상인지는 검사·연결 재계산과 같은 정의다({@link LinkableObjects}, 387). 매핑을 끄는 쪽과는 관계 유형
     * 잠금으로 줄 선다(부르는 쪽이 먼저 잡는다).
     */
    public List<LinkEnd> lockObjects(long firstId, long secondId) {
        return jdbc.sql(
                        """
                        select o.id, o.object_type_id, %s as linkable from object_instance o
                        where o.id in (:first, :second)
                        order by o.id
                        for share of o
                        """
                                .formatted(LinkableObjects.condition("o")))
                .param("first", firstId)
                .param("second", secondId)
                .query(
                        (rs, row) ->
                                new LinkEnd(
                                        rs.getLong("id"),
                                        rs.getLong("object_type_id"),
                                        rs.getBoolean("linkable")))
                .list();
    }

    /** 객체 하나의 속성 값(키는 속성 코드). 없으면 빈 값이다. 수동 연결에 저장 당시 비교 값을 남길 때 쓴다(366). */
    public Map<String, Object> properties(long objectId) {
        return jdbc.sql("select properties::text from object_instance where id = :id")
                .param("id", objectId)
                .query(String.class)
                .optional()
                .map(text -> json.readValue(text, PROPERTIES))
                .orElse(Map.of());
    }

    /** 출발 객체에서 나가는 활성 규칙 연결 수. 도착 객체가 원천에서 삭제된 연결은 세지 않는다(366). */
    public long countActiveRuleFromSource(long linkTypeId, long sourceObjectId) {
        return jdbc.sql(
                        """
                        select count(*) from object_link l
                        join object_instance o on o.id = l.target_object_id
                        where l.link_type_id = :type and l.source_object_id = :object
                          and l.origin = 'RULE' and l.status = 'ACTIVE' and o.status = 'ACTIVE'
                        """)
                .param("type", linkTypeId)
                .param("object", sourceObjectId)
                .query(Long.class)
                .single();
    }

    /** 관계 유형·출발·도착 쌍의 연결 행(모든 출처·상태). {@code uq_object_link}로 쌍마다 하나다. */
    public Optional<PairLink> findPair(long linkTypeId, long sourceObjectId, long targetObjectId) {
        return jdbc.sql(
                        """
                        select id, origin, status, link_mapping_id from object_link
                        where link_type_id = :type and source_object_id = :source and target_object_id = :target
                        """)
                .param("type", linkTypeId)
                .param("source", sourceObjectId)
                .param("target", targetObjectId)
                .query(
                        (rs, row) -> {
                            long mapping = rs.getLong("link_mapping_id");
                            Long linkMappingId = rs.wasNull() ? null : mapping;
                            return new PairLink(
                                    rs.getLong("id"),
                                    rs.getString("origin"),
                                    "ACTIVE".equals(rs.getString("status")),
                                    linkMappingId);
                        })
                .optional();
    }

    /** 출발 객체에서 나가는 활성 연결 수(모든 출처). 도착 객체가 원천에서 삭제된 연결은 세지 않는다. */
    public long countActiveFromSource(long linkTypeId, long sourceObjectId) {
        return jdbc.sql(
                        """
                        select count(*) from object_link l
                        join object_instance o on o.id = l.target_object_id
                        where l.link_type_id = :type and l.source_object_id = :object
                          and l.status = 'ACTIVE' and o.status = 'ACTIVE'
                        """)
                .param("type", linkTypeId)
                .param("object", sourceObjectId)
                .query(Long.class)
                .single();
    }

    /** 도착 객체로 들어오는 활성 연결 수(모든 출처). 출발 객체가 원천에서 삭제된 연결은 세지 않는다. */
    public long countActiveToTarget(long linkTypeId, long targetObjectId) {
        return jdbc.sql(
                        """
                        select count(*) from object_link l
                        join object_instance o on o.id = l.source_object_id
                        where l.link_type_id = :type and l.target_object_id = :object
                          and l.status = 'ACTIVE' and o.status = 'ACTIVE'
                        """)
                .param("type", linkTypeId)
                .param("object", targetObjectId)
                .query(Long.class)
                .single();
    }

    /** 확정 연결을 새로 넣는다. 만든 연결 ID를 돌려준다. */
    public long insert(
            long linkTypeId,
            long sourceObjectId,
            long targetObjectId,
            String origin,
            long confirmedBy,
            Long proposalItemId,
            Map<String, Object> evidence) {
        return jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, evidence,
                             confirmed_by, proposal_item_id)
                        values (:type, :source, :target, :origin, cast(:evidence as jsonb), :confirmedBy, :proposal)
                        returning id
                        """)
                .param("type", linkTypeId)
                .param("source", sourceObjectId)
                .param("target", targetObjectId)
                .param("origin", origin)
                .param("evidence", json.writeValueAsString(evidence))
                .param("confirmedBy", confirmedBy)
                .param("proposal", proposalItemId)
                .query(Long.class)
                .single();
    }

    /**
     * 비활성 연결 행을 확정 연결로 되살린다. 규칙 연결이었으면 근거 규칙을 떼고 출처를 바꾼다. 그 뒤로 규칙 재계산은 이 행을 건드리지
     * 않는다(MANUAL·DOCUMENT는 재계산 대상이 아님, 결정 U6).
     */
    public void reactivate(
            long linkId,
            String origin,
            long confirmedBy,
            Long proposalItemId,
            Map<String, Object> evidence) {
        jdbc.sql(
                        """
                        update object_link
                        set origin = :origin, link_mapping_id = null, status = 'ACTIVE', inactivated_at = null,
                            evidence = cast(:evidence as jsonb), confirmed_by = :confirmedBy,
                            proposal_item_id = :proposal, updated_at = now()
                        where id = :id and status = 'INACTIVE'
                        """)
                .param("id", linkId)
                .param("origin", origin)
                .param("evidence", json.writeValueAsString(evidence))
                .param("confirmedBy", confirmedBy)
                .param("proposal", proposalItemId)
                .update();
    }

    /**
     * 원천 키 값 하나로 객체를 찾는다(문서 연결 후보의 {@code documentId}·{@code targetObjectRef}). 키 컬럼이 하나인 매핑으로 만든
     * 객체만 본다. 원천 삭제한 객체도 돌려준다(저장 서비스가 상태를 검사해 이유를 알린다).
     *
     * @param resourceKey {@code source_resource.resource_key}. null이면 원천 표를 따지지 않는다
     */
    public List<Long> findBySingleSourceKey(
            long objectTypeId, String resourceKey, String keyValue) {
        return jdbc.sql(
                        """
                        select oi.id from object_instance oi
                        join object_source_mapping m on m.id = oi.source_mapping_id
                        join source_resource r on r.id = m.source_resource_id
                        where oi.object_type_id = :type
                          and (cast(:resource as varchar) is null or r.resource_key = cast(:resource as varchar))
                          and jsonb_array_length(m.key_columns) = 1
                          and oi.source_key ->> (m.key_columns ->> 0) = :value
                        order by oi.id
                        """)
                .param("type", objectTypeId)
                .param("resource", resourceKey)
                .param("value", keyValue)
                .query(Long.class)
                .list();
    }

    /**
     * 연결의 한쪽 끝 객체.
     *
     * @param active 연결 대상인지({@link LinkableObjects}: ACTIVE이고 꺼진 매핑에서 오지 않음)
     */
    public record LinkEnd(long id, long objectTypeId, boolean active) {}

    /**
     * @param origin RULE·MANUAL·DOCUMENT
     * @param linkMappingId RULE 연결의 근거 규칙 개정본. 확정 연결이면 null
     */
    public record PairLink(long id, String origin, boolean active, Long linkMappingId) {}
}
