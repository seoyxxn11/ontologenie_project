package com.axcore.platform.check;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 검사 요청·규칙 변환 테스트가 쓰는 정의·매핑·수집·검사 행을 DB에 직접 만든다. */
public record CheckRunTestFixtures(JdbcClient jdbc) {

    public long insertAdmin() {
        return jdbc.sql(
                        """
                        insert into account (
                            login_id, display_name, password_hash, role_id, must_change_password
                        )
                        values (
                            :loginId, '검사 테스트 관리자', 'test-hash',
                            (select id from role where code = 'ADMIN'), false
                        )
                        returning id
                        """)
                .param("loginId", "check-test-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    /**
     * 코드 앞에 붙일 이름을 받아 겹치지 않는 객체 유형을 만든다. 실제 코드는 {@link #typeCode}로 읽는다. ADMIN이 보는 그룹(PRODUCTION)에
     * 둔다. 검사 결과·문제 응답은 보는 사람의 조회 범위로 값을 가리므로(403), 그룹이 없으면 값이 응답에 나오지 않는다.
     */
    public long insertObjectType(String prefix) {
        return jdbc.sql(
                        "insert into object_type (code, name, access_group) values (:code, :code, 'PRODUCTION') returning id")
                .param("code", prefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                .query(Long.class)
                .single();
    }

    public String typeCode(long objectTypeId) {
        return jdbc.sql("select code from object_type where id = :id")
                .param("id", objectTypeId)
                .query(String.class)
                .single();
    }

    public long insertProperty(long objectTypeId, String code) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:typeId, :code, :code, 'STRING')
                        returning id
                        """)
                .param("typeId", objectTypeId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    /** 반영 중 규칙을 새로 넣을 관계 유형. 관계 유형마다 반영 중 규칙은 하나라(V17), 이미 있으면 양 끝·관계 수가 같은 관계 유형을 새로 만들어 돌려준다. */
    public long linkTypeForNewActiveRule(long linkTypeId) {
        boolean taken =
                jdbc.sql(
                                "select exists (select 1 from link_mapping where link_type_id = :id and status = 'ACTIVE')")
                        .param("id", linkTypeId)
                        .query(Boolean.class)
                        .single();
        if (!taken) {
            return linkTypeId;
        }
        return jdbc.sql(
                        """
                        insert into link_type (
                            code, name, source_type_id, target_type_id, cardinality, description
                        )
                        select :code, name, source_type_id, target_type_id, cardinality, description
                        from link_type where id = :id
                        returning id
                        """)
                .param("code", "LINK_" + UUID.randomUUID())
                .param("id", linkTypeId)
                .query(Long.class)
                .single();
    }

    public long insertLinkType(long sourceTypeId, long targetTypeId, String cardinality) {
        return jdbc.sql(
                        """
                        insert into link_type (
                            code, name, source_type_id, target_type_id, cardinality, description
                        )
                        values (:code, '검사 테스트 관계', :source, :target, :cardinality, '검사 테스트')
                        returning id
                        """)
                .param("code", "LINK_" + UUID.randomUUID())
                .param("source", sourceTypeId)
                .param("target", targetTypeId)
                .param("cardinality", cardinality)
                .query(Long.class)
                .single();
    }

    public long insertLinkMapping(long linkTypeId, String status, String sourceFilter) {
        return jdbc.sql(
                        """
                        insert into link_mapping (
                            link_type_id, code, revision, status, retired_at, source_filter
                        )
                        values (
                            :linkTypeId, :code, 1, :status,
                            case when cast(:status as varchar) = 'RETIRED' then now() end,
                            cast(:filter as jsonb)
                        )
                        returning id
                        """)
                .param("linkTypeId", linkTypeId)
                .param("code", "MAP_" + UUID.randomUUID())
                .param("status", status)
                .param("filter", sourceFilter)
                .query(Long.class)
                .single();
    }

    public void insertCondition(
            long linkMappingId,
            int sequenceNo,
            long sourcePropertyId,
            long targetPropertyId,
            String sourceTransforms,
            String targetTransforms) {
        jdbc.sql(
                        """
                        insert into link_mapping_condition (
                            link_mapping_id, source_property_id, target_property_id, sequence_no,
                            source_transforms, target_transforms
                        )
                        values (
                            :mappingId, :sourceProperty, :targetProperty, :seq,
                            cast(:sourceTransforms as jsonb), cast(:targetTransforms as jsonb)
                        )
                        """)
                .param("mappingId", linkMappingId)
                .param("sourceProperty", sourcePropertyId)
                .param("targetProperty", targetPropertyId)
                .param("seq", sequenceNo)
                .param("sourceTransforms", sourceTransforms)
                .param("targetTransforms", targetTransforms)
                .update();
    }

    public long insertDataSource() {
        return jdbc.sql(
                        """
                        insert into data_source (code, name, source_kind)
                        values (:code, '검사 테스트 원천', 'POSTGRES')
                        returning id
                        """)
                .param("code", "SRC_" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    public long insertResource(long dataSourceId, String resourceKey) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind)
                        values (:sourceId, :key, 'TABLE')
                        returning id
                        """)
                .param("sourceId", dataSourceId)
                .param("key", resourceKey)
                .query(Long.class)
                .single();
    }

    /** 객체 매핑을 만들고 그 ID를 돌려준다. */
    public long insertObjectMapping(long resourceId, long objectTypeId, boolean active) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (
                            source_resource_id, object_type_id, key_columns, is_active
                        )
                        values (:resourceId, :typeId, '["id"]'::jsonb, :active)
                        returning id
                        """)
                .param("resourceId", resourceId)
                .param("typeId", objectTypeId)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    /** 매핑에서 수집한 객체. 원천 키는 {"id": key}다. mappingId가 null이면 플랫폼에서 만든 객체다. */
    public long insertObject(
            Long mappingId, long objectTypeId, String key, String propertiesJson, String status) {
        return jdbc.sql(
                        """
                        insert into object_instance (
                            object_type_id, source_mapping_id, source_key, display_name,
                            properties, status
                        )
                        values (
                            :typeId, :mappingId,
                            case when cast(:mappingId as bigint) is not null
                                then jsonb_build_object('id', cast(:key as varchar)) end,
                            :key, cast(:properties as jsonb), :status
                        )
                        returning id
                        """)
                .param("typeId", objectTypeId)
                .param("mappingId", mappingId, Types.BIGINT)
                .param("key", key)
                .param("properties", propertiesJson)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    /** 실행기가 가져갈 대기 중 검사. scope·data_basis는 접수(060)가 저장하는 형식이다. */
    public long insertQueuedRun(
            String triggerKind, String scopeJson, String basisJson, Long requestedBy) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, scope, work_key, data_basis, requested_by,
                            compared_to_run_id
                        )
                        values (
                            :kind, 'QUEUED', cast(:scope as jsonb), :workKey, cast(:basis as jsonb),
                            :requestedBy,
                            case when cast(:kind as varchar) = 'REVERIFY'
                                then (select min(id) from check_run) end
                        )
                        returning id
                        """)
                .param("kind", triggerKind)
                .param("scope", scopeJson)
                .param("workKey", triggerKind + ":" + UUID.randomUUID())
                .param("basis", basisJson)
                .param("requestedBy", requestedBy, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    /** finishedAt에 끝난 수집 한 번과 그 수집의 자원 행(status). */
    public long insertSync(
            long dataSourceId, long resourceId, long accountId, String status, Instant finishedAt) {
        long syncRunId =
                jdbc.sql(
                                """
                                insert into sync_run (
                                    data_source_id, trigger_kind, status, requested_by,
                                    started_at, finished_at, error_message
                                )
                                values (
                                    :sourceId, 'MANUAL', :status, :accountId, :at, :at,
                                    case when cast(:status as varchar) = 'FAILED' then '테스트 실패' end
                                )
                                returning id
                                """)
                        .param("sourceId", dataSourceId)
                        .param("status", status)
                        .param("accountId", accountId)
                        .param("at", Timestamp.from(finishedAt), Types.TIMESTAMP)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into sync_run_resource (
                            sync_run_id, data_source_id, source_resource_id, mode, status,
                            started_at, finished_at, error_message
                        )
                        values (
                            :runId, :sourceId, :resourceId, 'FULL', :status, :at, :at,
                            case when cast(:status as varchar) = 'FAILED' then '테스트 실패' end
                        )
                        """)
                .param("runId", syncRunId)
                .param("sourceId", dataSourceId)
                .param("resourceId", resourceId)
                .param("status", status)
                .param("at", Timestamp.from(finishedAt), Types.TIMESTAMP)
                .update();
        return syncRunId;
    }

    /** 이미 끝났거나 진행 중인 검사 실행. 재검증 비교 대상이나 합류 대상으로 쓴다. */
    public long insertRun(String triggerKind, String status, Long requestedBy, String workKey) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key, requested_by, requested_at,
                            started_at, finished_at, error_message, compared_to_run_id
                        )
                        values (
                            :kind, :status, :workKey, :requestedBy, now(),
                            case when cast(:status as varchar) <> 'QUEUED' then now() end,
                            case when cast(:status as varchar) in ('SUCCEEDED', 'FAILED') then now() end,
                            case when cast(:status as varchar) = 'FAILED' then '테스트 실패' end,
                            case when cast(:kind as varchar) = 'REVERIFY'
                                then (select min(id) from check_run) end
                        )
                        returning id
                        """)
                .param("kind", triggerKind)
                .param("status", status)
                .param("requestedBy", requestedBy, Types.BIGINT)
                .param("workKey", workKey)
                .query(Long.class)
                .single();
    }

    public void setRequestedAt(long checkRunId, Instant requestedAt) {
        jdbc.sql("update check_run set requested_at = :at where id = :id")
                .param("at", Timestamp.from(requestedAt), Types.TIMESTAMP)
                .param("id", checkRunId)
                .update();
    }

    public void setStatus(long checkRunId, String status) {
        jdbc.sql(
                        """
                        update check_run
                        set status = :status,
                            started_at = coalesce(started_at, now()),
                            finished_at = case when cast(:status as varchar) in ('SUCCEEDED', 'FAILED')
                                then now() end,
                            error_message = case when cast(:status as varchar) = 'FAILED'
                                then '테스트 실패' end
                        where id = :id
                        """)
                .param("status", status)
                .param("id", checkRunId)
                .update();
    }

    /** 다른 테스트가 남긴 규칙이 ALL 범위에 섞이지 않도록 이 트랜잭션 안에서 모두 내린다. */
    public void retireAllRules() {
        jdbc.sql(
                        "update link_mapping set status = 'RETIRED', retired_at = now()"
                                + " where status = 'ACTIVE'")
                .update();
    }
}
