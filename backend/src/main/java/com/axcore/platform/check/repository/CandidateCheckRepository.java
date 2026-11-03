package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class CandidateCheckRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public List<ResourceCheckpoint> loadBasis(
            long proposalId, int revision, Set<Long> objectTypeIds, Long analysisRunId) {
        // 빈 IN 목록을 피하되 실제 ID와 겹치지 않는 값을 쓴다.
        Set<Long> types = objectTypeIds.isEmpty() ? Set.of(-1L) : objectTypeIds;
        return jdbc.sql(
                        """
                with candidate_payloads as (
                    select payload from proposal_revision where proposal_item_id=:proposal and revision=:revision
                    union all
                    select payload from proposal_option where proposal_item_id=:proposal and revision=:revision
                    union all
                    select payload from proposal_item where analysis_run_id=:analysis
                        and item_kind in ('OBJECT_MAPPING','PROPERTY_MAPPING') and review_status <> 'REJECTED'
                ), resource_references as (
                    select payload->>'sourceId' source_code, payload->>'resourceKey' resource_key
                    from candidate_payloads
                    union
                    select payload#>>'{sourceColumn,sourceId}', payload#>>'{sourceColumn,table}'
                    from candidate_payloads
                    union
                    select payload#>>'{sourceColumn,sourceId}',
                        (payload#>>'{sourceColumn,sourceId}') || '.' || (payload#>>'{sourceColumn,table}')
                    from candidate_payloads
                ), resources as (
                    select source_resource_id from object_source_mapping
                    where object_type_id in (:types)
                    union
                    select source_resource_id from analysis_run_target where analysis_run_id=:analysis
                    union
                    select sr.id from resource_references ref
                    join data_source ds on ds.code=ref.source_code
                    join source_resource sr on sr.data_source_id=ds.id and sr.resource_key=ref.resource_key
                )
                select r.source_resource_id, (
                    select s.sync_run_id from sync_run_resource s
                    where s.source_resource_id=r.source_resource_id and s.status='SUCCEEDED'
                    order by s.finished_at desc, s.sync_run_id desc limit 1
                ) last_sync
                from resources r order by r.source_resource_id
                """)
                .param("proposal", proposalId)
                .param("revision", revision)
                .param("types", types)
                .param("analysis", analysisRunId, java.sql.Types.BIGINT)
                .query(
                        (rs, row) ->
                                new ResourceCheckpoint(
                                        rs.getLong("source_resource_id"),
                                        rs.getObject("last_sync", Long.class)))
                .list();
    }

    public long enqueue(
            long id, int revision, long accountId, String workKey, Object basis, Instant now) {
        var existing =
                jdbc.sql(
                                """
                select id from check_run where work_key=:key and status in ('QUEUED','RUNNING','SUCCEEDED')
                order by case when status='SUCCEEDED' then 0 else 1 end, id desc limit 1
                """)
                        .param("key", workKey)
                        .query(Long.class)
                        .optional();
        if (existing.isPresent()) return existing.get();
        return jdbc.sql(
                        """
                insert into check_run(trigger_kind, status, scope, work_key, data_basis, requested_by, requested_at)
                values ('CANDIDATE','QUEUED',
                    jsonb_build_object('proposalItems',jsonb_build_array(jsonb_build_object('proposalItemId',cast(:id as bigint),'revision',cast(:revision as integer)))),
                    :key, cast(:basis as jsonb), :account, :now)
                on conflict (work_key) where status in ('QUEUED','RUNNING')
                do update set work_key=excluded.work_key returning id
                """)
                .param("id", id)
                .param("revision", revision)
                .param("key", workKey)
                .param("basis", json.writeValueAsString(basis))
                .param("account", accountId)
                .param("now", java.sql.Timestamp.from(now))
                .query(Long.class)
                .single();
    }
}
