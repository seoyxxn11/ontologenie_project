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
    private static final String CHECKS =
            """
            select distinct c.id from check_run c,
                jsonb_array_elements(case when jsonb_typeof(c.scope -> 'proposalItems') = 'array'
                    then c.scope -> 'proposalItems' else '[]'::jsonb end) e
            where c.trigger_kind = 'CANDIDATE'
              and (e ->> 'proposalItemId')::bigint in (select id from proposal_item where analysis_run_id = :run)
            """;

    // 이 분석의 제안이 이미 쓰인 곳. 하나라도 있으면 지우지 않는다.
    private static final Map<String, String> BLOCKERS = new LinkedHashMap<>();

    static {
        // 배치가 지금 실행 중인 후보 검사. 대기(QUEUED)는 아직 아무도 집지 않았으므로 함께 지운다.
        BLOCKERS.put(
                "check_run(실행 중)",
                "select count(*) from check_run where status = 'RUNNING' and id in ("
                        + CHECKS
                        + ")");
        // 승인·거절·보류 결정만 있는 제안은 테스트 기록이라 함께 지운다. 실제로 반영된 제안이 있으면 지우지 않는다.
        BLOCKERS.put(
                "proposal_item(반영됨)",
                "select count(*) from proposal_item where analysis_run_id = :run"
                        + " and apply_status <> 'NOT_APPLIED'");
        BLOCKERS.put(
                "held_item",
                "select count(*) from held_item where proposal_item_id in (" + ITEMS + ")");
        BLOCKERS.put(
                "issue",
                "select count(*) from issue where proposal_item_id in ("
                        + ITEMS
                        + ") or detected_check_run_id in ("
                        + CHECKS
                        + ") or resolved_check_run_id in ("
                        + CHECKS
                        + ")");
        BLOCKERS.put(
                "issue_verification",
                "select count(*) from issue_verification where check_run_id in (" + CHECKS + ")");
        BLOCKERS.put(
                "issue_history",
                "select count(*) from issue_history where check_run_id in (" + CHECKS + ")");
        BLOCKERS.put(
                "check_run",
                "select count(*) from check_run where compared_to_run_id in ("
                        + CHECKS
                        + ") and id not in ("
                        + CHECKS
                        + ")");
        BLOCKERS.put(
                "check_result",
                "select count(*) from check_result where proposal_item_id in ("
                        + ITEMS
                        + ") and check_run_id not in ("
                        + CHECKS
                        + ")");
        for (String table :
                List.of(
                        "object_type",
                        "property_definition",
                        "link_type",
                        "object_source_mapping",
                        "link_mapping",
                        "object_link")) {
            BLOCKERS.put(
                    table,
                    "select count(*) from " + table + " where proposal_item_id in (" + ITEMS + ")");
        }
    }

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
        jdbc.sql(ITEMS + " order by id for update").param("run", runId).query(Long.class).list();
        jdbc.sql("select id from check_run where id in (" + CHECKS + ") order by id for update")
                .param("run", runId)
                .query(Long.class)
                .list();
    }

    /** 이 분석의 제안을 이미 참조하는 표와 건수. 비어 있어야 지울 수 있다. */
    public Map<String, Long> blockers(long runId) {
        Map<String, Long> found = new LinkedHashMap<>();
        BLOCKERS.forEach(
                (table, sql) -> {
                    long count = jdbc.sql(sql).param("run", runId).query(Long.class).single();
                    if (count > 0) found.put(table, count);
                });
        return found;
    }

    /**
     * 후보 검사(검사 결과 포함) → 결정 → 선택지 → 개정본 → 제안 → 분석 실행(분석 대상 포함) 순으로 지운다. 개정본·선택지의 수정·삭제 금지 트리거는 이
     * 트랜잭션 안에서만 끄고 다시 켠다. 제안과 현재 개정본은 서로 가리키므로 그 확인은 지운 뒤로 미룬다.
     */
    public Map<String, Integer> delete(long runId) {
        jdbc.sql("set constraints all immediate").update();
        jdbc.sql("alter table proposal_revision disable trigger trg_proposal_revision_immutable")
                .update();
        jdbc.sql("alter table proposal_option disable trigger trg_proposal_option_immutable")
                .update();
        jdbc.sql("set constraints fk_proposal_item_current_revision deferred").update();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put(
                "checkRuns",
                jdbc.sql("delete from check_run where id in (" + CHECKS + ")")
                        .param("run", runId)
                        .update());
        // 결정은 개정본·선택지를 가리키므로 먼저 지운다(반영되지 않은 테스트 결정만 남아 있다)
        deleted.put(
                "decisions",
                jdbc.sql("delete from review_decision where proposal_item_id in (" + ITEMS + ")")
                        .param("run", runId)
                        .update());
        deleted.put(
                "options",
                jdbc.sql("delete from proposal_option where proposal_item_id in (" + ITEMS + ")")
                        .param("run", runId)
                        .update());
        deleted.put(
                "revisions",
                jdbc.sql("delete from proposal_revision where proposal_item_id in (" + ITEMS + ")")
                        .param("run", runId)
                        .update());
        deleted.put(
                "proposals",
                jdbc.sql("delete from proposal_item where analysis_run_id = :run")
                        .param("run", runId)
                        .update());
        deleted.put(
                "analysisRuns",
                jdbc.sql("delete from analysis_run where id = :run").param("run", runId).update());
        jdbc.sql("set constraints all immediate").update();
        jdbc.sql("alter table proposal_revision enable trigger trg_proposal_revision_immutable")
                .update();
        jdbc.sql("alter table proposal_option enable trigger trg_proposal_option_immutable")
                .update();
        return deleted;
    }
}
