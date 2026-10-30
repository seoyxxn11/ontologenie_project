package com.axcore.platform.check.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.domain.CheckResult;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 PostgreSQL에서 검사 결과 매핑과 조건별 페이지 조회를 검증한다. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CheckResultRepositoryTests {

    @Autowired CheckResultRepository repository;

    @Autowired JdbcClient jdbcClient;

    private long accountId;

    @BeforeEach
    void setUp() {
        accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (
                                    login_id, display_name, password_hash, role_id
                                )
                                values (
                                    :loginId, '검사 조회 테스트 사용자', 'test-hash',
                                    (select id from role where code = 'ADMIN')
                                )
                                returning id
                                """)
                        .param("loginId", "check-reader-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void readsCountsAndJsonEvidence() {
        long runId = insertRun();
        long proposalId = insertProposal();
        long resultId = insertResult(runId, null, proposalId);

        CheckResult result = repository.findById(resultId).orElseThrow();

        assertThat(result.getCheckRunId()).isEqualTo(runId);
        assertThat(result.getProposalItemId()).isEqualTo(proposalId);
        assertThat(result.getProposalItemRevision()).isEqualTo(1);
        assertThat(result.getLinkMappingId()).isNull();
        assertThat(result.getPreviousResultId()).isNull();

        assertThat(result.getTotalCount()).isEqualTo(10);
        assertThat(result.getMatchedCount()).isEqualTo(7);
        assertThat(result.getUnmatchedCount()).isEqualTo(1);
        assertThat(result.getDuplicateCount()).isEqualTo(1);
        assertThat(result.getViolationCount()).isEqualTo(1);

        assertThat(result.getSampleRows()).hasSize(1);
        assertThat(result.getSampleRows().get(0))
                .containsEntry("rawValue", "W23")
                .containsEntry("transformedValue", "E023")
                .containsEntry("classification", "UNMATCHED")
                .containsEntry("reason", "대상 객체 없음");
    }

    @Test
    void returnsOnlyResultsFromRequestedRun() {
        long runId = insertRun();
        long otherRunId = insertRun();
        long proposalId = insertProposal();

        long expectedId = insertResult(runId, null, proposalId);
        insertResult(otherRunId, null, proposalId);

        Page<CheckResult> result = search(runId, null, null, 0, 20);

        assertThat(result.getContent()).extracting(CheckResult::getId).containsExactly(expectedId);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void filtersByProposalWithinRequestedRun() {
        long runId = insertRun();
        long otherRunId = insertRun();
        long proposalId = insertProposal();
        long otherProposalId = insertProposal();

        long expectedId = insertResult(runId, null, proposalId);
        insertResult(runId, null, otherProposalId);
        insertResult(otherRunId, null, proposalId);

        Page<CheckResult> result = search(runId, null, proposalId, 0, 20);

        assertThat(result.getContent()).extracting(CheckResult::getId).containsExactly(expectedId);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void filtersByMappingWithinRequestedRun() {
        long runId = insertRun();
        long otherRunId = insertRun();
        long mappingId = insertMapping();
        long otherMappingId = insertMapping();

        long expectedId = insertResult(runId, mappingId, null);
        insertResult(runId, otherMappingId, null);
        insertResult(otherRunId, mappingId, null);

        Page<CheckResult> result = search(runId, mappingId, null, 0, 20);

        assertThat(result.getContent()).extracting(CheckResult::getId).containsExactly(expectedId);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void paginatesOnlyResultsFromRequestedRun() {
        long runId = insertRun();
        long otherRunId = insertRun();

        long firstId = insertResult(runId, null, insertProposal());

        // 다른 실행의 결과를 중간에 넣어도 페이지와 전체 건수에서 제외되어야 한다.
        insertResult(otherRunId, null, insertProposal());

        long secondId = insertResult(runId, null, insertProposal());
        long thirdId = insertResult(runId, null, insertProposal());

        Page<CheckResult> firstPage = search(runId, null, null, 0, 2);
        Page<CheckResult> secondPage = search(runId, null, null, 1, 2);

        assertThat(firstPage.getContent())
                .extracting(CheckResult::getId)
                .containsExactly(firstId, secondId);
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getSize()).isEqualTo(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(secondPage.getContent()).extracting(CheckResult::getId).containsExactly(thirdId);
        assertThat(secondPage.getNumber()).isEqualTo(1);
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    void returnsEmptyWhenNoResultMatchesProposalFilter() {
        long runId = insertRun();
        insertResult(runId, null, insertProposal());

        Page<CheckResult> result = search(runId, null, Long.MAX_VALUE, 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    private Page<CheckResult> search(
            long runId, Long mappingId, Long proposalId, int page, int size) {

        return repository.findAll(
                CheckResultSpecifications.byFilters(runId, mappingId, proposalId),
                PageRequest.of(page, size, Sort.by("id").ascending()));
    }

    private long insertRun() {
        return jdbcClient
                .sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key,
                            requested_by, started_at, finished_at
                        )
                        values (
                            'MANUAL', 'SUCCEEDED', :workKey,
                            :accountId, now(), now()
                        )
                        returning id
                        """)
                .param("workKey", "check-result-test-" + UUID.randomUUID())
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private long insertProposal() {
        // 현재 후보와, 그 후보가 참조하는 불변 revision을 함께 준비한다.
        return jdbcClient
                .sql(
                        """
                        with inserted as (
                            insert into proposal_item (
                                origin, item_kind, payload, created_by
                            )
                            values (
                                'MANUAL', 'LINK_RULE', '{}'::jsonb, :accountId
                            )
                            returning *
                        )
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, created_by
                        )
                        select id, revision, payload, evidence, created_by
                        from inserted
                        returning proposal_item_id
                        """)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private long insertMapping() {
        String suffix = UUID.randomUUID().toString();

        // 승인 규칙의 외래 키에 필요한 출발·도착 유형과 관계 유형도 준비한다.
        return jdbcClient
                .sql(
                        """
                        with source_type as (
                            insert into object_type (code, name)
                            values (:sourceCode, '테스트 출발 유형')
                            returning id
                        ),
                        target_type as (
                            insert into object_type (code, name)
                            values (:targetCode, '테스트 도착 유형')
                            returning id
                        ),
                        relation_type as (
                            insert into link_type (
                                code, name, source_type_id, target_type_id,
                                cardinality, description
                            )
                            select
                                :linkCode, '테스트 관계', s.id, t.id,
                                'MANY_TO_ONE', '검사 결과 조회 테스트'
                            from source_type s
                            cross join target_type t
                            returning id
                        )
                        insert into link_mapping (link_type_id, code, revision)
                        select id, :mappingCode, 1
                        from relation_type
                        returning id
                        """)
                .param("sourceCode", "SRC_" + suffix)
                .param("targetCode", "DST_" + suffix)
                .param("linkCode", "LINK_" + suffix)
                .param("mappingCode", "MAP_" + suffix)
                .query(Long.class)
                .single();
    }

    private long insertResult(long runId, Long mappingId, Long proposalId) {
        Integer revision = proposalId == null ? null : 1;

        return jdbcClient
                .sql(
                        """
                        insert into check_result (
                            check_run_id, link_mapping_id,
                            proposal_item_id, proposal_item_revision,
                            total_count, matched_count, unmatched_count,
                            duplicate_count, violation_count, sample_rows
                        )
                        values (
                            :runId, :mappingId, :proposalId, :revision,
                            10, 7, 1, 1, 1,
                            '[{
                                "rawValue": "W23",
                                "transformedValue": "E023",
                                "classification": "UNMATCHED",
                                "reason": "대상 객체 없음"
                            }]'::jsonb
                        )
                        returning id
                        """)
                .param("runId", runId)
                .param("mappingId", mappingId, Types.BIGINT)
                .param("proposalId", proposalId, Types.BIGINT)
                .param("revision", revision, Types.INTEGER)
                .query(Long.class)
                .single();
    }
}
