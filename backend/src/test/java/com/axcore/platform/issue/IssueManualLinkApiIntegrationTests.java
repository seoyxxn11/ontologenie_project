package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueManualLinkApiIntegrationTests {

    private static final String PATH = "/api/v1/issues/{issueId}/manual-links";
    private static final String REASON = "인사 발령 기록을 확인했습니다.";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean AuditRecorder audits;

    private long accountId;
    private long issueId;
    private long sourceId;
    private long targetId;
    private long linkTypeId;
    private long mappingId;
    private long runId;

    @BeforeEach
    void setUp() {
        var fixtures = new IssueTestFixtures(jdbc);
        accountId = fixtures.insertAdmin();
        mappingId = fixtures.insertLinkMapping();
        runId = fixtures.insertRun("MANUAL", "SUCCEEDED", Instant.now(), null, accountId);
        issueId = fixtures.insertIssue(runId, mappingId, "UNMATCHED", "CHECKING", "{}");
        linkTypeId =
                jdbc.sql("select link_type_id from link_mapping where id = :id")
                        .param("id", mappingId)
                        .query(Long.class)
                        .single();
        sourceId = insertObject("source_type_id");
        targetId = insertObject("target_type_id");
        jdbc.sql("update issue set object_instance_id = :sourceId where id = :issueId")
                .param("sourceId", sourceId)
                .param("issueId", issueId)
                .update();
    }

    @Test
    void storesManualLinkAndAuditsItWithoutChangingIssueState() throws Exception {
        String maxLengthReason = "가".repeat(1000);
        send(targetId, 0, maxLengthReason, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueId").value(issueId))
                .andExpect(jsonPath("$.data.linkId").isNumber())
                .andExpect(jsonPath("$.data.outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.actionExecutionId").isNumber());

        assertThat(
                        jdbc.sql(
                                        """
                                        select origin, status, confirmed_by, evidence->>'reason' as reason
                                        from object_link where source_object_id = :sourceId and target_object_id = :targetId
                                        """)
                                .param("sourceId", sourceId)
                                .param("targetId", targetId)
                                .query()
                                .singleRow())
                .containsEntry("origin", "MANUAL")
                .containsEntry("status", "ACTIVE")
                .containsEntry("confirmed_by", accountId)
                .containsEntry("reason", maxLengthReason);
        assertThat(
                        jdbc.sql(
                                        """
                                        select e.status, e.actor_id, e.input->>'reason' as reason, d.code
                                        from action_execution e join action_definition d on e.action_definition_id = d.id
                                        join entity_change c on c.action_execution_id = e.id
                                        where e.target_type = 'issue' and e.target_id = :id and c.table_name = 'object_link'
                                        """)
                                .param("id", issueId)
                                .query()
                                .singleRow())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("actor_id", accountId)
                .containsEntry("reason", maxLengthReason)
                .containsEntry("code", "CREATE_ISSUE_MANUAL_LINK");
        assertThat(
                        jdbc.sql("select status, row_version from issue where id = :id")
                                .param("id", issueId)
                                .query()
                                .singleRow())
                .containsEntry("status", "CHECKING")
                .containsEntry("row_version", 0);
    }

    @Test
    void rollsBackLinkAndSuccessAuditWhenAuditRecordingFails() throws Exception {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            doAnswer(
                            invocation -> {
                                invocation.callRealMethod();
                                throw new IllegalStateException("수동 연결 감사 기록 실패 테스트");
                            })
                    .when(audits)
                    .record(any());

            send(targetId, 0, REASON, "ADMIN")
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

            assertNoLinks();
            assertThat(
                            jdbc.sql(
                                            """
                                            select status from action_execution
                                            where target_type = 'issue' and target_id = :id
                                            """)
                                    .param("id", issueId)
                                    .query(String.class)
                                    .list())
                    .containsExactly("FAILED");
        } finally {
            cleanUpCommittedFixtures();
        }
    }

    @Test
    void reactivatesAnInactivePairAndRejectsAnActiveDuplicate() throws Exception {
        long linkId =
                jdbc.sql(
                                """
                                insert into object_link (
                                    link_type_id, source_object_id, target_object_id,
                                    origin, status, inactivated_at, confirmed_by
                                )
                                values (:type, :source, :target, 'MANUAL', 'INACTIVE', now(), :account)
                                returning id
                                """)
                        .param("type", linkTypeId)
                        .param("source", sourceId)
                        .param("target", targetId)
                        .param("account", accountId)
                        .query(Long.class)
                        .single();
        send(targetId, 0, REASON, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkId").value(linkId))
                .andExpect(jsonPath("$.data.outcome").value("REACTIVATED"));

        send(targetId, 0, REASON, "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LINK_ALREADY_EXISTS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NEW", "FIXED_PENDING_REVERIFY"})
    void rejectsIssuesOutsideChecking(String issueStatus) throws Exception {
        new IssueTestFixtures(jdbc).setStatus(issueId, issueStatus);
        send(targetId, 0, REASON, "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.currentStatus").value(issueStatus));
        assertNoLinks();
    }

    @Test
    void rejectsStaleVersion() throws Exception {
        send(targetId, 1, REASON, "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertNoLinks();
    }

    @Test
    void rejectsNonAdmin() throws Exception {
        send(targetId, 0, REASON, "VIEWER").andExpect(status().isForbidden());
        assertNoLinks();
    }

    @Test
    void rejectsWrongTargetType() throws Exception {
        send(insertObject("source_type_id"), 0, REASON, "ADMIN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("LINK_END_TYPE_MISMATCH"));
        assertNoLinks();
    }

    private void assertNoLinks() {
        assertThat(
                        jdbc.sql("select count(*) from object_link where source_object_id = :id")
                                .param("id", sourceId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private void cleanUpCommittedFixtures() {
        var typeIds =
                jdbc.sql("select source_type_id, target_type_id from link_type where id = :id")
                        .param("id", linkTypeId)
                        .query()
                        .singleRow();
        jdbc.sql(
                        """
                        delete from entity_change where action_execution_id in (
                            select id from action_execution where target_type = 'issue' and target_id = :id
                        )
                        """)
                .param("id", issueId)
                .update();
        jdbc.sql("delete from action_execution where target_type = 'issue' and target_id = :id")
                .param("id", issueId)
                .update();
        jdbc.sql("delete from object_link where source_object_id = :id")
                .param("id", sourceId)
                .update();
        jdbc.sql("delete from issue where id = :id").param("id", issueId).update();
        jdbc.sql("delete from check_run where id = :id").param("id", runId).update();
        jdbc.sql("delete from link_mapping where id = :id").param("id", mappingId).update();
        jdbc.sql("delete from link_type where id = :id").param("id", linkTypeId).update();
        jdbc.sql("delete from object_instance where id in (:source, :target)")
                .param("source", sourceId)
                .param("target", targetId)
                .update();
        jdbc.sql("delete from object_type where id in (:source, :target)")
                .param("source", typeIds.get("source_type_id"))
                .param("target", typeIds.get("target_type_id"))
                .update();
        jdbc.sql("delete from account where id = :id").param("id", accountId).update();
    }

    private long insertObject(String typeColumn) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name)
                        select %s, '수동 연결 테스트 객체' from link_type where id = :id
                        returning id
                        """
                                .formatted(typeColumn))
                .param("id", linkTypeId)
                .query(Long.class)
                .single();
    }

    private ResultActions send(long target, long version, String reason, String role)
            throws Exception {
        var account = new LoginAccount(accountId, "issue-test", role, false);
        return mockMvc.perform(
                post(PATH, issueId)
                        .with(authentication(account.toAuthentication()))
                        .with(IssueTestFixtures.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"targetObjectId": %d, "reason": "%s", "rowVersion": %d}
                                """
                                        .formatted(target, reason, version)));
    }
}
