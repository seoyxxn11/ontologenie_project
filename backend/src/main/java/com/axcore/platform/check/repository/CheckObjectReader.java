package com.axcore.platform.check.repository;

import com.axcore.platform.ontology.repository.LinkableObjects;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 실행기(191)가 판정할 객체를 읽는다. 검사는 원천이 아니라 수집한 플랫폼 객체를 본다(결정 문서 2026-11-05 9.1).
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): {@code object_instance}는 아직 Entity가 없는 표라
 * 연결 재계산(181 {@code RuleLinkRepository})·수집 저장과 같은 방식으로 읽는다. 원천 자원을 알려면 {@code
 * object_source_mapping}을 같이 join해야 한다. 읽기 전용이다.
 */
@Repository
@RequiredArgsConstructor
public class CheckObjectReader {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /**
     * 유형의 연결 대상 객체({@link LinkableObjects}, 연결 재계산과 같은 정의, 387) 가운데 주어진 원천 자원에서 수집한 것. 원천에서 지운
     * 객체(DELETED_AT_SOURCE), 꺼진 매핑에서 온 객체, 플랫폼에서 만든 객체(원천 키 없음)는 빠진다. 객체 ID 순서다.
     */
    public List<CheckObject> activeObjects(long objectTypeId, Collection<Long> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        select o.id, m.source_resource_id, o.source_key::text as source_key,
                               o.properties::text as properties
                        from object_instance o
                        join object_source_mapping m on m.id = o.source_mapping_id
                        where o.object_type_id = :type and %s
                          and m.source_resource_id = any(cast(:resources as bigint[]))
                        order by o.id
                        """
                                .formatted(LinkableObjects.condition("o")))
                .param("type", objectTypeId)
                .param("resources", resourceIds.toArray(Long[]::new))
                .query(
                        (rs, row) ->
                                new CheckObject(
                                        rs.getLong("id"),
                                        rs.getLong("source_resource_id"),
                                        json.readValue(rs.getString("source_key"), MAP),
                                        json.readValue(rs.getString("properties"), MAP)))
                .list();
    }

    /**
     * 관계 유형의 활성 수동 연결(origin=MANUAL)을 출발 객체별 도착 객체 ID로 읽는다(366). 양 끝 객체가 모두 연결 대상({@link
     * LinkableObjects})인 것만이다(387). 검사는 규칙으로 미연결인 출발에 이 연결이 있으면 정상으로 본다.
     */
    public Map<Long, List<Long>> manualLinkTargets(long linkTypeId) {
        Map<Long, List<Long>> targets = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        select l.source_object_id, l.target_object_id
                        from object_link l
                        join object_instance s on s.id = l.source_object_id
                        join object_instance t on t.id = l.target_object_id
                        where l.link_type_id = :type and l.origin = 'MANUAL' and l.status = 'ACTIVE'
                          and %s and %s
                        order by l.source_object_id, l.target_object_id
                        """
                                .formatted(
                                        LinkableObjects.condition("s"),
                                        LinkableObjects.condition("t")))
                .param("type", linkTypeId)
                .query(
                        (rs, row) ->
                                targets.computeIfAbsent(
                                                rs.getLong("source_object_id"),
                                                id -> new ArrayList<>())
                                        .add(rs.getLong("target_object_id")))
                .list();
        return targets;
    }

    /** 객체 하나. properties의 키는 속성 코드다(유형 코드를 붙이지 않음). */
    public record CheckObject(
            long id,
            long sourceResourceId,
            Map<String, Object> sourceKey,
            Map<String, Object> properties) {}
}
