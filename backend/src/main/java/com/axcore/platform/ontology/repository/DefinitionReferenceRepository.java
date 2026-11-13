package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.dto.DefinitionReference;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 정의를 참조하는 다른 영역의 행을 찾는다. 참조하는 테이블 대부분은 다른 도메인 소유라 Entity 대신 SQL로 읽는다. */
@Repository
@RequiredArgsConstructor
public class DefinitionReferenceRepository {

    /** IN_USE 응답에 담는 참조 행 ID의 최대 개수. */
    static final int MAX_IDS = 20;

    /** 객체 유형을 막는 참조 (API 025). 조건의 :id는 객체 유형 ID다. */
    private static final List<ReferenceSource> OBJECT_TYPE_REFERENCES =
            List.of(
                    new ReferenceSource("property_definition", "object_type_id = :id"),
                    new ReferenceSource("link_type", ":id in (source_type_id, target_type_id)"),
                    new ReferenceSource("object_source_mapping", "object_type_id = :id"),
                    new ReferenceSource("object_instance", "object_type_id = :id"),
                    new ReferenceSource("action_definition", "target_object_type_id = :id"));

    /**
     * 속성을 막는 참조 (API 029). 조건의 :id는 속성 ID다. 객체는 그 유형의 객체 중 이 속성 코드에 null이 아닌 값이 있는 것이다. 연결 규칙 비교
     * 항목은 RETIRED 개정본의 것도 막는다(개정 이력을 지우지 않으므로).
     */
    private static final List<ReferenceSource> PROPERTY_REFERENCES =
            List.of(
                    new ReferenceSource("object_type", "display_property_id = :id"),
                    new ReferenceSource("property_source_mapping", "property_definition_id = :id"),
                    new ReferenceSource(
                            "link_mapping_condition",
                            ":id in (source_property_id, target_property_id)"),
                    new ReferenceSource(
                            "object_instance",
                            """
                            object_type_id = (select object_type_id from property_definition where id = :id)
                            and coalesce(jsonb_typeof(properties -> (
                                    select code from property_definition where id = :id)), 'null') <> 'null'
                            """));

    /**
     * 관계 유형을 막는 참조 (API 032·033). 조건의 :id는 관계 유형 ID다. 지난 규칙 개정본(RETIRED)·비활성 연결(INACTIVE)·처리된 보류 건도
     * 이력이므로 막는다.
     */
    private static final List<ReferenceSource> LINK_TYPE_REFERENCES =
            List.of(
                    new ReferenceSource("link_mapping", "link_type_id = :id"),
                    new ReferenceSource("object_link", "link_type_id = :id"));

    private final JdbcClient jdbcClient;

    public List<DefinitionReference> findObjectTypeReferences(Long objectTypeId) {
        return findReferences(OBJECT_TYPE_REFERENCES, objectTypeId);
    }

    public List<DefinitionReference> findPropertyReferences(Long propertyId) {
        return findReferences(PROPERTY_REFERENCES, propertyId);
    }

    public List<DefinitionReference> findLinkTypeReferences(Long linkTypeId) {
        return findReferences(LINK_TYPE_REFERENCES, linkTypeId);
    }

    private List<DefinitionReference> findReferences(List<ReferenceSource> sources, Long id) {
        List<DefinitionReference> references = new ArrayList<>();
        for (ReferenceSource source : sources) {
            long count =
                    jdbcClient
                            .sql(
                                    "select count(*) from "
                                            + source.table()
                                            + " where "
                                            + source.condition())
                            .param("id", id)
                            .query(Long.class)
                            .single();
            if (count == 0) {
                continue;
            }
            List<Long> ids =
                    jdbcClient
                            .sql(
                                    "select id from "
                                            + source.table()
                                            + " where "
                                            + source.condition()
                                            + " order by id limit "
                                            + MAX_IDS)
                            .param("id", id)
                            .query(Long.class)
                            .list();
            references.add(new DefinitionReference(source.table(), count, ids));
        }
        return references;
    }

    /** 참조 테이블과 조건. 이 클래스의 상수로만 만들며 요청 값으로 만들지 않는다. */
    private record ReferenceSource(String table, String condition) {}
}
