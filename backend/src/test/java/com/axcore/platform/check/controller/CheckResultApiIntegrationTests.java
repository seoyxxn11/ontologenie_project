package com.axcore.platform.check.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** API 062를 실제 Service·Repository·PostgreSQL까지 연결해 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class CheckResultApiIntegrationTests {

    private static final String PATH = "/api/v1/checks/runs/{id}/results";

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbcClient;

    private long accountId;
    private String loginId;

    @BeforeEach
    void prepareAccount() {
        loginId = "check-api-" + UUID.randomUUID();

        accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (
                                    login_id, display_name, password_hash,
                                    role_id, must_change_password
                                )
                                values (
                                    :loginId, '통합 테스트 관리자', 'test-hash',
                                    (select id from role where code = 'ADMIN'),
                                    false
                                )
                                returning id
                                """)
                        .param("loginId", loginId)
                        .query(Long.class)
                        .single();
    }

    @Test
    void readsRealCountsAndEvidence() throws Exception {
        long runId = insertRun("SUCCEEDED");
        long proposalId = insertProposal();
        long resultId = insertResult(runId, null, proposalId);

        mockMvc.perform(get(PATH, runId).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(resultId))
                .andExpect(jsonPath("$.data[0].checkRunId").value(runId))
                .andExpect(jsonPath("$.data[0].proposalItemId").value(proposalId))
                .andExpect(jsonPath("$.data[0].proposalItemRevision").value(1))
                .andExpect(jsonPath("$.data[0].linkMappingId").hasJsonPath())
                .andExpect(jsonPath("$.data[0].linkMappingId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].totalCount").value(10))
                .andExpect(jsonPath("$.data[0].matchedCount").value(4))
                .andExpect(jsonPath("$.data[0].unmatchedCount").value(3))
                .andExpect(jsonPath("$.data[0].duplicateCount").value(2))
                .andExpect(jsonPath("$.data[0].violationCount").value(1))
                .andExpect(jsonPath("$.data[0].matchRate").value(0.4))
                .andExpect(jsonPath("$.data[0].sampleRows[0].rawValue").value("W23"))
                .andExpect(jsonPath("$.data[0].sampleRows[0].transformedValue").value("E023"))
                .andExpect(jsonPath("$.data[0].sampleRows[0].classification").value("UNMATCHED"))
                .andExpect(jsonPath("$.data[0].sampleRows[0].reason").value("대상 객체 없음"))
                .andExpect(jsonPath("$.data[0].aiConfidence").doesNotExist())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void filtersAndPagesOnlyRequestedRun() throws Exception {
        long runId = insertRun("SUCCEEDED");
        long otherRunId = insertRun("SUCCEEDED");

        long firstId = insertResult(runId, null, insertProposal());

        long secondProposalId = insertProposal();

        // 같은 후보라도 다른 검사 실행의 결과는 포함하지 않는다.
        insertResult(otherRunId, null, secondProposalId);

        long secondId = insertResult(runId, null, secondProposalId);
        long thirdId = insertResult(runId, null, insertProposal());

        mockMvc.perform(
                        get(PATH, runId)
                                .with(loggedIn("ADMIN"))
                                .param("page", "0")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(firstId))
                .andExpect(jsonPath("$.data[1].id").value(secondId))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mockMvc.perform(
                        get(PATH, runId)
                                .with(loggedIn("ADMIN"))
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(thirdId))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(false));

        mockMvc.perform(
                        get(PATH, runId)
                                .with(loggedIn("ADMIN"))
                                .param("proposalItemId", String.valueOf(secondProposalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void filtersApprovedMappingResults() throws Exception {
        long runId = insertRun("SUCCEEDED");
        long otherRunId = insertRun("SUCCEEDED");

        long mappingId = insertMapping();
        long otherMappingId = insertMapping();

        long expectedId = insertResult(runId, mappingId, null);

        insertResult(runId, otherMappingId, null);
        insertResult(runId, null, insertProposal());
        insertResult(otherRunId, mappingId, null);

        mockMvc.perform(
                        get(PATH, runId)
                                .with(loggedIn("ADMIN"))
                                .param("linkMappingId", String.valueOf(mappingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(expectedId))
                .andExpect(jsonPath("$.data[0].linkMappingId").value(mappingId))
                .andExpect(jsonPath("$.data[0].proposalItemId").hasJsonPath())
                .andExpect(jsonPath("$.data[0].proposalItemId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].proposalItemRevision").hasJsonPath())
                .andExpect(jsonPath("$.data[0].proposalItemRevision").value(nullValue()))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void keepsInspectedRevisionAndPreviousResult() throws Exception {
        long proposalId = insertProposal();

        long previousRunId = insertRun("SUCCEEDED");
        long previousResultId = insertResult(previousRunId, null, proposalId);

        long runId = insertRun("SUCCEEDED");
        long resultId = insertResult(runId, null, proposalId);

        jdbcClient
                .sql(
                        """
                        update check_result
                        set previous_result_id = :previousResultId
                        where id = :resultId
                        """)
                .param("previousResultId", previousResultId)
                .param("resultId", resultId)
                .update();

        // 현재 후보가 수정되어도 과거 검사 결과는 검사 당시 revision을 반환한다.
        addRevision(proposalId, 2);

        mockMvc.perform(get(PATH, runId).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(resultId))
                .andExpect(jsonPath("$.data[0].proposalItemId").value(proposalId))
                .andExpect(jsonPath("$.data[0].proposalItemRevision").value(1))
                .andExpect(jsonPath("$.data[0].previousResultId").value(previousResultId));
    }

    @Test
    void returnsNullMatchRateWhenTotalCountIsZero() throws Exception {
        long runId = insertRun("SUCCEEDED");
        long resultId = insertResult(runId, null, insertProposal());

        // 전체 검사 건수가 0인 결과를 준비한다.
        jdbcClient
                .sql(
                        """
                        update check_result
                        set total_count = 0,
                            matched_count = 0,
                            unmatched_count = 0,
                            duplicate_count = 0,
                            violation_count = 0,
                            sample_rows = '[]'::jsonb
                        where id = :resultId
                        """)
                .param("resultId", resultId)
                .update();

        mockMvc.perform(get(PATH, runId).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].totalCount").value(0))
                .andExpect(jsonPath("$.data[0].matchRate").hasJsonPath())
                .andExpect(jsonPath("$.data[0].matchRate").value(nullValue()))
                .andExpect(jsonPath("$.data[0].sampleRows").isArray())
                .andExpect(jsonPath("$.data[0].sampleRows").isEmpty());
    }

    @Test
    void returnsEmptyForUnmatchedFilter() throws Exception {
        long runId = insertRun("SUCCEEDED");
        insertResult(runId, null, insertProposal());

        mockMvc.perform(
                        get(PATH, runId)
                                .with(loggedIn("ADMIN"))
                                .param("proposalItemId", String.valueOf(Long.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void returnsEmptyForSucceededRunWithoutResults() throws Exception {
        long runId = insertRun("SUCCEEDED");

        mockMvc.perform(get(PATH, runId).with(loggedIn("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void hidesPartialResultsForNonSucceededRun(String runStatus) throws Exception {
        long runId = insertRun(runStatus);

        // 결과 행이 이미 있어도 성공 전에는 노출하지 않아야 한다.
        insertResult(runId, null, insertProposal());

        mockMvc.perform(
                        get(PATH, runId)
                                .with(loggedIn("ADMIN"))
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void returnsNotFoundForUnknownRun() throws Exception {
        mockMvc.perform(get(PATH, Long.MAX_VALUE).with(loggedIn("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("check_run"))
                .andExpect(jsonPath("$.error.details.id").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @Test
    void requiresAuthentication() throws Exception {
        long runId = insertRun("SUCCEEDED");
        insertResult(runId, null, insertProposal());

        mockMvc.perform(get(PATH, runId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNER", "MAINTENANCE", "VIEWER"})
    void rejectsNonAdminRole(String roleCode) throws Exception {
        long runId = insertRun("SUCCEEDED");
        insertResult(runId, null, insertProposal());

        jdbcClient
                .sql(
                        """
                        update account
                        set role_id = (select id from role where code = :roleCode)
                        where id = :accountId
                        """)
                .param("roleCode", roleCode)
                .param("accountId", accountId)
                .update();

        mockMvc.perform(get(PATH, runId).with(loggedIn(roleCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(accountId, loginId, roleCode, false);

        return authentication(account.toAuthentication());
    }

    private long insertRun(String runStatus) {
        return jdbcClient
                .sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key,
                            requested_by, started_at, finished_at, error_message
                        )
                        values (
                            'MANUAL',
                            :status,
                            :workKey,
                            :accountId,
                            case when :status <> 'QUEUED' then now() end,
                            case
                                when :status in ('SUCCEEDED', 'FAILED') then now()
                            end,
                            case
                                when :status = 'FAILED' then '통합 테스트 검사 실패'
                            end
                        )
                        returning id
                        """)
                .param("status", runStatus)
                .param("workKey", "check-api-run-" + UUID.randomUUID())
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private long insertProposal() {
        // 현재 후보와 그 후보가 참조하는 불변 revision을 함께 준비한다.
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

    private void addRevision(long proposalId, int revision) {
        jdbcClient
                .sql(
                        """
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, created_by
                        )
                        select id, :revision, payload, evidence, created_by
                        from proposal_item
                        where id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .update();

        jdbcClient
                .sql(
                        """
                        update proposal_item
                        set revision = :revision
                        where id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .update();
    }

    private long insertMapping() {
        String suffix = UUID.randomUUID().toString();

        // 승인 규칙이 참조하는 객체 유형과 관계 유형도 함께 준비한다.
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
                                'MANY_TO_ONE', '검사 결과 통합 테스트'
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
                            10, 4, 3, 2, 1,
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
