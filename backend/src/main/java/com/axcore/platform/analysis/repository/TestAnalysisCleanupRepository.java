package com.axcore.platform.analysis.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 테스트로 돌린 분석 실행과 그 제안을 지운다(S15P31C106-307, 시연 전 없앨 임시 기능). 없앨 때는 TestAnalysisCleanup* 세 파일과 그 테스트를
 * 지운다.
 */
@Repository
@RequiredArgsConstructor
public class TestAnalysisCleanupRepository {
    private static final String ITEMS = "select id from proposal_item where analysis_run_id = :run";

    // 이 제안을 대상으로 한 후보 검사(p.id 자리에 제안 번호)
    private static final String CHECKS_OF_ITEM =
            """
            select c.id from check_run c,
                jsonb_array_elements(case when jsonb_typeof(c.scope -> 'proposalItems') = 'array'
                    then c.scope -> 'proposalItems' else '[]'::jsonb end) e
            where c.trigger_kind = 'CANDIDATE' and (e ->> 'proposalItemId')::bigint = p.id
            """;

    /**
     * 지워도 되는 제안: 반영되지 않았고 어디에도 쓰이지 않은 제안. 반영된 제안, 온톨로지·보류·이슈가 가리키는 제안, 후보 검사가 실행 중이거나 그 검사가 이슈·재검증에
     * 쓰인 제안은 남긴다. 승인·거절·보류 결정만 있는 제안은 테스트 기록이라 함께 지운다.
     */
    private static final String DELETABLE_ITEMS =
            """
            select p.id from proposal_item p
            where p.analysis_run_id = :run and p.apply_status = 'NOT_APPLIED'
              and not exists (select 1 from issue i where i.proposal_item_id = p.id)
              and not exists (select 1 from object_type t where t.proposal_item_id = p.id)
              and not exists (select 1 from property_definition t where t.proposal_item_id = p.id)
              and not exists (select 1 from link_type t where t.proposal_item_id = p.id)
              and not exists (select 1 from object_source_mapping t where t.proposal_item_id = p.id)
              and not exists (select 1 from link_mapping t where t.proposal_item_id = p.id)
              and not exists (select 1 from object_link t where t.proposal_item_id = p.id)
              and not exists (select 1 from check_result r
                  where r.proposal_item_id = p.id and r.check_run_id not in (%1$s))
              and not exists (select 1 from check_run c where c.id in (%1$s)
                  and (c.status = 'RUNNING'
                    or exists (select 1 from issue i
                        where i.detected_check_run_id = c.id or i.resolved_check_run_id = c.id)
                    or exists (select 1 from issue_verification v where v.check_run_id = c.id)
                    or exists (select 1 from issue_history h where h.check_run_id = c.id)
                    or exists (select 1 from check_run c2 where c2.compared_to_run_id = c.id)))
            order by p.id
            """
                    .formatted(CHECKS_OF_ITEM);

    // 지울 제안을 대상으로 한 후보 검사
    private static final String CHECKS_OF_IDS =
            """
            select distinct c.id from check_run c,
                jsonb_array_elements(case when jsonb_typeof(c.scope -> 'proposalItems') = 'array'
                    then c.scope -> 'proposalItems' else '[]'::jsonb end) e
            where c.trigger_kind = 'CANDIDATE' and (e ->> 'proposalItemId')::bigint in (:ids)
            """;

    private final JdbcClient jdbc;

    /** 끝난(SUCCEEDED·FAILED) 분석 실행 번호. 지우는 동안 바뀌지 않게 잠근다. */
    public List<Long> finishedRunIds() {
        return jdbc.sql(
                        "select id from analysis_run where status in ('SUCCEEDED','FAILED') order by id for update")
                .query(Long.class)
                .list();
    }

    /**
     * 이 분석의 제안과 후보 검사 행을 잠근다. 결정(승인·거절)도 제안 행을 먼저 잠그므로, 동시에 눌러도 한쪽이 끝난 뒤 다른 쪽이 최신 상태로 다시 확인한다. 배치가
     * 후보 검사를 실행 중으로 바꾸는 것도 이 잠금이 끝날 때까지 기다린다.
     */
    public void lockParts(long runId) {
        List<Long> items =
                jdbc.sql(ITEMS + " order by id for update")
                        .param("run", runId)
                        .query(Long.class)
                        .list();
        if (items.isEmpty()) return;
        jdbc.sql(
                        "select id from check_run where id in ("
                                + CHECKS_OF_IDS
                                + ") order by id for update")
                .param("ids", items)
                .query(Long.class)
                .list();
    }

    /** 이 분석에서 지워도 되는 제안 번호. */
    public List<Long> deletableItems(long runId) {
        return jdbc.sql(DELETABLE_ITEMS).param("run", runId).query(Long.class).list();
    }

    /** 이 분석에 남아 있는 제안 수. */
    public int itemCount(long runId) {
        return jdbc.sql("select count(*) from proposal_item where analysis_run_id = :run")
                .param("run", runId)
                .query(Integer.class)
                .single();
    }

    /**
     * 후보 검사(검사 결과 포함) → 결정 → 선택지 → 개정본 → 제안 순으로 지우고, 남은 제안이 없으면 분석 실행(분석 대상 포함)도 지운다. 개정본·선택지의
     * 수정·삭제 금지 트리거는 이 트랜잭션 안에서만 끄고 다시 켠다. 제안과 현재 개정본은 서로 가리키므로 그 확인은 지운 뒤로 미룬다.
     */
    public Map<String, Integer> delete(long runId, List<Long> ids) {
        jdbc.sql("set constraints all immediate").update();
        jdbc.sql("alter table proposal_revision disable trigger trg_proposal_revision_immutable")
                .update();
        jdbc.sql("alter table proposal_option disable trigger trg_proposal_option_immutable")
                .update();
        jdbc.sql("set constraints fk_proposal_item_current_revision deferred").update();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        if (!ids.isEmpty()) deleteItems(ids, deleted);
        deleted.put(
                "analysisRuns",
                itemCount(runId) == 0
                        ? jdbc.sql("delete from analysis_run where id = :run")
                                .param("run", runId)
                                .update()
                        : 0);
        jdbc.sql("set constraints all immediate").update();
        jdbc.sql("alter table proposal_revision enable trigger trg_proposal_revision_immutable")
                .update();
        jdbc.sql("alter table proposal_option enable trigger trg_proposal_option_immutable")
                .update();
        return deleted;
    }

    private void deleteItems(List<Long> ids, Map<String, Integer> deleted) {
        deleted.put(
                "checkRuns",
                jdbc.sql("delete from check_run where id in (" + CHECKS_OF_IDS + ")")
                        .param("ids", ids)
                        .update());
        // 결정은 개정본·선택지를 가리키므로 먼저 지운다(반영되지 않은 테스트 결정만 남아 있다)
        deleted.put(
                "decisions",
                jdbc.sql("delete from review_decision where proposal_item_id in (:ids)")
                        .param("ids", ids)
                        .update());
        deleted.put(
                "options",
                jdbc.sql("delete from proposal_option where proposal_item_id in (:ids)")
                        .param("ids", ids)
                        .update());
        deleted.put(
                "revisions",
                jdbc.sql("delete from proposal_revision where proposal_item_id in (:ids)")
                        .param("ids", ids)
                        .update());
        deleted.put(
                "proposals",
                jdbc.sql("delete from proposal_item where id in (:ids)")
                        .param("ids", ids)
                        .update());
    }
}
