package com.axcore.platform.ontology.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연결 재계산(181)이 쓰는 객체(object_instance) 읽기와 연결(object_link) 읽기·쓰기, 관계 유형 잠금.
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): object_instance·object_link는 아직 Entity가
 * 없는 테이블이라 기존 Repository 방식(수집 저장 {@link ObjectSyncRepository})을 따른다. 새 Entity는 담당 도메인과 정한 뒤에 만든다.
 * 관계 유형 잠금은 advisory lock이라 SQL로만 잡는다. 같은 트랜잭션에서 이 표들을 Entity로 다루는 코드가 없으므로 flush·clear는 필요 없다.
 */
@Repository
@RequiredArgsConstructor
public class RuleLinkRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /**
     * 관계 유형 하나의 연결을 쓰는 트랜잭션끼리 줄 세우는 잠금(트랜잭션 끝까지). 규칙 재계산(181)과 확정 연결 저장(329)이 같은 이름을 써야 같은 쌍을 동시에
     * 넣지 않는다.
     */
    public void lockLinkType(long linkTypeId) {
        lock("link:type:" + linkTypeId);
    }

    /** 이름 하나에 트랜잭션 끝까지 걸리는 잠금. */
    public void lock(String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:key))")
                .param("key", key)
                .query()
                .singleRow();
    }

    /** 객체들의 유형 ID. ID를 배열 하나로 넘겨 바인딩 변수 수 상한(32767)에 걸리지 않게 한다. 없는 ID는 무시한다. */
    public Set<Long> objectTypeIdsOf(Collection<Long> objectIds) {
        if (objectIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                jdbc.sql(
                                "select distinct object_type_id from object_instance"
                                        + " where id = any(cast(:ids as bigint[]))")
                        .param("ids", objectIds.toArray(Long[]::new))
                        .query(Long.class)
                        .list());
    }

    /**
     * 유형의 연결 대상 객체({@link LinkableObjects}). 원천에서 지운 객체(DELETED_AT_SOURCE)와 꺼진 매핑에서 온 객체는 빠진다. 검사가
     * 판정하는 범위와 같다(387).
     */
    public List<LinkableObject> activeObjects(long objectTypeId) {
        return jdbc.sql(
                        """
                        select o.id, o.properties::text as properties from object_instance o
                        where o.object_type_id = :type and %s
                        order by o.id
                        """
                                .formatted(LinkableObjects.condition("o")))
                .param("type", objectTypeId)
                .query(
                        (rs, row) ->
                                new LinkableObject(
                                        rs.getLong("id"),
                                        json.readValue(rs.getString("properties"), MAP)))
                .list();
    }

    /** 관계 유형의 모든 연결(모든 출처·상태). 연결의 양 끝 객체가 모두 연결 대상({@link LinkableObjects})인지도 같이 읽는다. */
    public List<StoredLink> linksOf(long linkTypeId) {
        return jdbc.sql(
                        """
                        select l.id, l.source_object_id, l.target_object_id, l.origin, l.status,
                               l.link_mapping_id, l.evidence::text as evidence,
                               (%s and %s) as ends_active
                        from object_link l
                        join object_instance s on s.id = l.source_object_id
                        join object_instance t on t.id = l.target_object_id
                        where l.link_type_id = :type
                        """
                                .formatted(
                                        LinkableObjects.condition("s"),
                                        LinkableObjects.condition("t")))
                .param("type", linkTypeId)
                .query(this::storedLink)
                .list();
    }

    /** 규칙이 계산한 새 연결. 만든 연결 ID를 돌려준다. */
    public long insertRuleLink(
            long linkTypeId,
            long sourceObjectId,
            long targetObjectId,
            long linkMappingId,
            Map<String, Object> evidence) {
        return jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, link_mapping_id, evidence)
                        values (:type, :source, :target, 'RULE', :rule, cast(:evidence as jsonb))
                        returning id
                        """)
                .param("type", linkTypeId)
                .param("source", sourceObjectId)
                .param("target", targetObjectId)
                .param("rule", linkMappingId)
                .param("evidence", json.writeValueAsString(evidence))
                .query(Long.class)
                .single();
    }

    /** 규칙 연결을 활성으로 두고 근거를 바꾼다. 비활성이면 되살린다. */
    public void confirmRuleLink(long linkId, long linkMappingId, Map<String, Object> evidence) {
        jdbc.sql(
                        """
                        update object_link
                        set status = 'ACTIVE', inactivated_at = null, link_mapping_id = :rule,
                            evidence = cast(:evidence as jsonb), updated_at = now()
                        where id = :id and origin = 'RULE'
                        """)
                .param("id", linkId)
                .param("rule", linkMappingId)
                .param("evidence", json.writeValueAsString(evidence))
                .update();
    }

    /** 활성 규칙 연결을 끈다. 지우지 않는다(결정 U6). */
    public void inactivateRuleLinks(Collection<Long> linkIds) {
        if (linkIds.isEmpty()) {
            return;
        }
        jdbc.sql(
                        """
                        update object_link
                        set status = 'INACTIVE', inactivated_at = now(), updated_at = now()
                        where id = any(cast(:ids as bigint[])) and origin = 'RULE' and status = 'ACTIVE'
                        """)
                .param("ids", linkIds.toArray(Long[]::new))
                .update();
    }

    /** 활성 수동 연결을 끈다(366). 지우지 않는다. */
    public void inactivateManualLinks(Collection<Long> linkIds) {
        if (linkIds.isEmpty()) {
            return;
        }
        jdbc.sql(
                        """
                        update object_link
                        set status = 'INACTIVE', inactivated_at = now(), updated_at = now()
                        where id = any(cast(:ids as bigint[])) and origin = 'MANUAL' and status = 'ACTIVE'
                        """)
                .param("ids", linkIds.toArray(Long[]::new))
                .update();
    }

    /**
     * 규칙이 원하는 쌍의 수동 연결(활성·비활성)을 그 규칙의 연결로 바꾼다(366). 같은 쌍은 {@code uq_object_link}로 한 행뿐이라 새로 넣지 않고 이
     * 행을 쓴다. 승인자({@code confirmed_by})·근거 후보는 이력으로 남긴다.
     */
    public void convertManualToRuleLink(
            long linkId, long linkMappingId, Map<String, Object> evidence) {
        jdbc.sql(
                        """
                        update object_link
                        set origin = 'RULE', status = 'ACTIVE', inactivated_at = null,
                            link_mapping_id = :rule, evidence = cast(:evidence as jsonb), updated_at = now()
                        where id = :id and origin = 'MANUAL'
                        """)
                .param("id", linkId)
                .param("rule", linkMappingId)
                .param("evidence", json.writeValueAsString(evidence))
                .update();
    }

    private StoredLink storedLink(ResultSet rs, int row) throws SQLException {
        long mapping = rs.getLong("link_mapping_id");
        Long linkMappingId = rs.wasNull() ? null : mapping;
        return new StoredLink(
                rs.getLong("id"),
                rs.getLong("source_object_id"),
                rs.getLong("target_object_id"),
                rs.getString("origin"),
                "ACTIVE".equals(rs.getString("status")),
                linkMappingId,
                json.readValue(rs.getString("evidence"), MAP),
                rs.getBoolean("ends_active"));
    }

    /** 연결 계산에 쓰는 객체: ID와 속성 값. */
    public record LinkableObject(long id, Map<String, Object> properties) {}

    /**
     * @param origin RULE·MANUAL·DOCUMENT
     * @param endsActive 출발·도착 객체가 모두 연결 대상인지({@link LinkableObjects}: ACTIVE이고 꺼진 매핑에서 오지 않음)
     */
    public record StoredLink(
            long id,
            long sourceObjectId,
            long targetObjectId,
            String origin,
            boolean active,
            Long linkMappingId,
            Map<String, Object> evidence,
            boolean endsActive) {

        public boolean isRule() {
            return "RULE".equals(origin);
        }

        public boolean isManual() {
            return "MANUAL".equals(origin);
        }

        public boolean isDocument() {
            return "DOCUMENT".equals(origin);
        }
    }
}
