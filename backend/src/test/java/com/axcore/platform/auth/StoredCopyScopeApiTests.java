package com.axcore.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장된 객체 값 사본을 돌려주는 API(S15P31C106-403)가 조회 범위를 지키는지 실제 보안 설정과 DB로 확인한다. 문제(065·066·070), 검사 결과
 * 표본(062, 승인 규칙·후보 시험), 재검증 비교, 제안 근거(049 상세·개정 이력)를 본다.
 *
 * <ul>
 *   <li>ADMIN은 그룹의 일반 값은 보지만, 민감 속성을 조건으로 쓴 규칙의 값과 그룹 없는 유형의 값은 받지 못한다. 저장 뒤 속성이 민감으로 바뀐 경우와 같다(값은
 *       일반일 때 저장됐다).
 *   <li>VIEWER는 이 API를 쓸 수 없다(403).
 *   <li>민감 값을 볼 수 있는 허용 행이면 값을 받고 열람 기록(394)이 남는다. 기록에 값 원문은 없다.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class StoredCopyScopeApiTests {

    private static final String PASSWORD = "Passw0rd!";

    private static final String WITHHELD_TEXT = "조회 범위상 예시 값이 든 설명을 보여 주지 않습니다.";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Autowired MockMvc mockMvc;

    @Autowired JdbcClient jdbc;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired JsonMapper json;

    private String suffix;
    private long admin;
    private long viewer;
    private Rule normal;
    private Rule restricted;
    private Rule hidden;
    private Rule revealed;
    private long baselineRun;
    private long reverifyRun;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String group = "COPY_" + suffix;
        String revealGroup = "COPY_R_" + suffix;
        allow("ADMIN", group, false);
        allow("VIEWER", group, false);
        // 민감 값을 볼 수 있는 허용 행. 열람 기록을 확인하려고 ADMIN에게 이 그룹만 연다.
        allow("ADMIN", revealGroup, true);

        long source = type("SRC", group);
        long target = type("TGT", group);
        // 어느 역할에도 허용 행이 없는 그룹. 미분류(그룹 없음)는 관리자에게 보이므로(V29) 범위 밖 유형은 이렇게 만든다.
        long ungrouped = type("HID", "COPY_H_" + suffix);
        long revealSource = type("RSRC", revealGroup);
        long revealTarget = type("RTGT", revealGroup);
        Map<String, Long> sourceProps = properties(source);
        Map<String, Long> targetProps = properties(target);
        Map<String, Long> hiddenProps = properties(ungrouped);
        Map<String, Long> revealSourceProps = properties(revealSource);
        Map<String, Long> revealTargetProps = properties(revealTarget);

        admin = account("ADMIN");
        viewer = account("VIEWER");

        normal = rule("NORMAL", source, target, sourceProps.get("code"), targetProps.get("code"));
        restricted =
                rule(
                        "SECRET",
                        source,
                        target,
                        sourceProps.get("secret"),
                        targetProps.get("secret"));
        hidden =
                rule("HIDDEN", ungrouped, target, hiddenProps.get("code"), targetProps.get("code"));
        revealed =
                rule(
                        "REVEAL",
                        revealSource,
                        revealTarget,
                        revealSourceProps.get("secret"),
                        revealTargetProps.get("secret"));

        mapKeyColumns(source, target, sourceProps, targetProps);

        baselineRun = run("MANUAL", null);
        reverifyRun = run("REVERIFY", baselineRun);
        long objectId = 9_000_000L;
        for (Rule rule : List.of(normal, restricted, hidden, revealed)) {
            objectId++;
            rule.issueId = issue(rule);
            rule.objectId = objectId;
            checkResult(baselineRun, rule, objectId, "UNMATCHED");
            checkResult(reverifyRun, rule, objectId, "MATCHED");
            verification(rule.issueId, reverifyRun, rule);
        }
    }

    @Test
    void adminSeesNormalValuesButNotRestrictedOrUnlistedInIssues() throws Exception {
        Cookie session = login(admin);

        mockMvc.perform(get("/api/v1/issues/" + normal.issueId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawValue").value(normal.raw()))
                .andExpect(jsonPath("$.data.transformedValue").value(normal.transformed()))
                .andExpect(
                        jsonPath("$.data.evidence.transformedValues.1").value(normal.transformed()))
                .andExpect(jsonPath("$.data.restrictedFields").isEmpty());

        for (Rule rule : List.of(restricted, hidden)) {
            String body =
                    mockMvc.perform(get("/api/v1/issues/" + rule.issueId).cookie(session))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.rawValue").value(nullValue()))
                            .andExpect(jsonPath("$.data.transformedValue").value(nullValue()))
                            .andExpect(
                                    jsonPath("$.data.evidence.transformedValues")
                                            .value(nullValue()))
                            .andExpect(
                                    jsonPath(
                                            "$.data.restrictedFields",
                                            contains(
                                                    "rawValue",
                                                    "transformedValue",
                                                    "transformedValues")))
                            .andExpect(jsonPath("$.data.failureReason").exists())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(body).doesNotContain(rule.raw(), rule.transformed());
        }

        String list =
                mockMvc.perform(
                                get("/api/v1/issues")
                                        .param("linkMappingId", "" + restricted.id)
                                        .cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].rawValue").value(nullValue()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(list).doesNotContain(restricted.raw());
    }

    @Test
    void adminDoesNotSeeRestrictedOrUnlistedValuesInVerifications() throws Exception {
        Cookie session = login(admin);

        mockMvc.perform(get("/api/v1/issues/" + normal.issueId + "/verifications").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].detail.rawValue").value(normal.raw()));
        for (Rule rule : List.of(restricted, hidden)) {
            String body =
                    mockMvc.perform(
                                    get("/api/v1/issues/" + rule.issueId + "/verifications")
                                            .cookie(session))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data[0].detail.rawValue").value(nullValue()))
                            .andExpect(
                                    jsonPath("$.data[0].detail.transformedValue")
                                            .value(nullValue()))
                            .andExpect(
                                    jsonPath(
                                            "$.data[0].detail.restrictedFields",
                                            contains("rawValue", "transformedValue")))
                            .andExpect(jsonPath("$.data[0].detail.classification").exists())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(body).doesNotContain(rule.raw(), rule.transformed());
        }
    }

    @Test
    void adminDoesNotSeeRestrictedOrUnlistedValuesInCheckResults() throws Exception {
        Cookie session = login(admin);

        mockMvc.perform(
                        get("/api/v1/checks/runs/" + baselineRun + "/results")
                                .param("linkMappingId", "" + normal.id)
                                .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sampleRows[0].rawValue").value(normal.raw()))
                .andExpect(jsonPath("$.data[0].sampleRows[0].sourceKey.code").value(normal.key()))
                // 값은 보여 주지만, 민감 속성을 담는 키 컬럼(secret_key)과 매핑이 없는 키 컬럼(unmapped)은
                // 비운다(ObjectDisplaySql과 같다).
                .andExpect(
                        jsonPath("$.data[0].sampleRows[0].sourceKey.secret_key").value(nullValue()))
                .andExpect(
                        jsonPath("$.data[0].sampleRows[0].sourceKey.unmapped").value(nullValue()))
                .andExpect(
                        jsonPath(
                                "$.data[0].sampleRows[0].restrictedFields", contains("sourceKey")));

        for (Rule rule : List.of(restricted, hidden)) {
            String body =
                    mockMvc.perform(
                                    get("/api/v1/checks/runs/" + baselineRun + "/results")
                                            .param("linkMappingId", "" + rule.id)
                                            .cookie(session))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data[0].unmatchedCount").value(1))
                            .andExpect(
                                    jsonPath("$.data[0].sampleRows[0].rawValue").value(nullValue()))
                            .andExpect(
                                    jsonPath("$.data[0].sampleRows[0].sourceKey")
                                            .value(nullValue()))
                            .andExpect(
                                    jsonPath(
                                            "$.data[0].sampleRows[0].restrictedFields",
                                            contains("rawValue", "sourceKey", "transformedValue")))
                            .andExpect(
                                    jsonPath("$.data[0].sampleRows[0].classification")
                                            .value("UNMATCHED"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(body).doesNotContain(rule.raw(), rule.transformed(), rule.key());
        }
    }

    @Test
    void adminDoesNotSeeRestrictedOrUnlistedValuesInReverificationComparison() throws Exception {
        String body =
                mockMvc.perform(
                                get("/api/v1/checks/runs/" + reverifyRun + "/comparison")
                                        .param("size", "100")
                                        .cookie(login(admin)))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath(
                                        "$.data.rows[?(@.ruleCode == '%s')].before.rawValue"
                                                .formatted(normal.code),
                                        contains(normal.raw())))
                        .andExpect(
                                jsonPath(
                                        "$.data.rows[?(@.ruleCode == '%s')].after.rawValue"
                                                .formatted(restricted.code),
                                        contains(nullValue())))
                        .andExpect(
                                jsonPath(
                                        "$.data.rows[?(@.ruleCode == '%s')].before.restrictedFields[*]"
                                                .formatted(hidden.code),
                                        hasItems("rawValue", "transformedValue")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body)
                .doesNotContain(restricted.raw(), restricted.transformed())
                .doesNotContain(hidden.raw(), hidden.transformed());
    }

    @Test
    void adminDoesNotSeeRestrictedValuesInCandidateCheckSamples() throws Exception {
        long secretCandidate = candidate(restricted, "secret");
        long normalCandidate = candidate(normal, "code");
        Cookie session = login(admin);

        mockMvc.perform(get("/api/v1/checks/runs/" + normalCandidate + "/results").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sampleRows[0].rawValue").value("CAND-RAW"))
                .andExpect(jsonPath("$.data[0].sampleRows[0].targetKey.code").value("CAND-T"));
        String body =
                mockMvc.perform(
                                get("/api/v1/checks/runs/" + secretCandidate + "/results")
                                        .cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].sampleRows[0].rawValue").value(nullValue()))
                        .andExpect(jsonPath("$.data[0].sampleRows[0].targetKey").value(nullValue()))
                        .andExpect(
                                jsonPath(
                                        "$.data[0].sampleRows[0].restrictedFields",
                                        contains(
                                                "rawValue",
                                                "sourceKey",
                                                "targetKey",
                                                "transformedValue")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("CAND-RAW", "CAND-T");
    }

    @Test
    void adminDoesNotSeeRestrictedOrUnlistedExampleValuesInProposalEvidence() throws Exception {
        long proposal = proposalWithEvidence();
        Cookie session = login(admin);

        String body =
                mockMvc.perform(get("/api/v1/proposals/" + proposal).cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.evidence.items[0].values").isEmpty())
                        .andExpect(
                                jsonPath("$.data.evidence.items[0].snippet")
                                        .value("조회 범위상 예시 값을 보여 주지 않습니다."))
                        .andExpect(
                                jsonPath("$.data.evidence.items[0].locator")
                                        .value(schema() + ".tbl.secret_col"))
                        .andExpect(jsonPath("$.data.evidence.items[1].values[0]").value("CODE-V"))
                        .andExpect(jsonPath("$.data.evidence.items[2].values").isEmpty())
                        // 위치의 대소문자가 실제와 달라도 같은 컬럼으로 본다.
                        .andExpect(jsonPath("$.data.evidence.items[3].values").isEmpty())
                        // 가린 항목을 품은 근거의 자유 문장도 바꾼다. AI가 문장에 값 예시를 넣기도 한다.
                        .andExpect(
                                jsonPath("$.data.evidence.reason")
                                        .value("조회 범위상 예시 값이 든 설명을 보여 주지 않습니다."))
                        .andExpect(
                                jsonPath("$.data.evidence.holdReason")
                                        .value("조회 범위상 예시 값이 든 설명을 보여 주지 않습니다."))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("SECRET-V", "HIDDEN-V", "SECRET-UPPER");

        String revisions =
                mockMvc.perform(get("/api/v1/proposals/" + proposal + "/revisions").cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.data[0].evidence.items[1].values[0]").value("CODE-V"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(revisions).doesNotContain("SECRET-V", "HIDDEN-V");
    }

    /**
     * 원천 데이터 정책상 금지·민감 위치의 예시 값은 매핑이 없어도 가린다(397). 399 전에 저장된 근거와, 398에서 매핑을 지운 직원 직급 같은 컬럼의 예시 값이
     * 남아 있어서다. 매핑이 없는 일반 컬럼은 그대로 보인다.
     */
    @Test
    void storedEvidenceOfPolicyGovernedLocationsIsWithheldEvenWithoutMapping() throws Exception {
        long proposal =
                // 다른 시험이 세는 대기 중인 수동 연결 규칙과 겹치지 않게 거절한 속성 후보로 둔다
                proposal(
                        "PROPERTY",
                        "REJECTED",
                        """
                        {"reason": "직급 예: 대리",
                         "items": [
                           {"locator": "erp.employee.position", "values": ["대리", "과장"], "snippet": "대리, 과장"},
                           {"locator": "erp.attendance.work_type", "values": ["NORMAL"]},
                           {"locator": "mes.maintenance_order.technician", "values": ["T-KIM"]},
                           {"locator": "erp.employee.dept_code", "values": ["D10"]}
                         ]}
                        """);
        Cookie session = login(admin);

        String body =
                mockMvc.perform(get("/api/v1/proposals/" + proposal).cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.evidence.items[0].values").isEmpty())
                        .andExpect(jsonPath("$.data.evidence.items[1].values").isEmpty())
                        .andExpect(jsonPath("$.data.evidence.items[2].values").isEmpty())
                        .andExpect(jsonPath("$.data.evidence.items[3].values[0]").value("D10"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("과장", "NORMAL", "T-KIM", "직급 예: 대리");
    }

    /**
     * 그룹이 없는(미분류) 유형의 일반 값은 관리자에게 보인다(384 보완, V29). 유형을 승인한 뒤 그룹을 정하기 전에도, 그 유형이 걸린 규칙의 문제 값과 제안
     * 근거를 보고 검토할 수 있어야 한다. 민감 속성을 조건으로 쓴 규칙의 값은 미분류 유형이어도 가린다.
     */
    @Test
    void adminSeesNormalValuesOfUnclassifiedTypes() throws Exception {
        long unclassified = type("UNC", null);
        Map<String, Long> props = properties(unclassified);
        long target = typeId("TGT_" + suffix);
        Rule open =
                rule(
                        "UNC_NORMAL",
                        unclassified,
                        target,
                        props.get("code"),
                        propertyId(target, "code"));
        Rule secret =
                rule(
                        "UNC_SECRET",
                        unclassified,
                        target,
                        props.get("secret"),
                        propertyId(target, "secret"));
        open.issueId = issue(open);
        secret.issueId = issue(secret);
        Cookie session = login(admin);

        mockMvc.perform(get("/api/v1/issues/" + open.issueId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawValue").value(open.raw()))
                .andExpect(jsonPath("$.data.transformedValue").value(open.transformed()));
        mockMvc.perform(get("/api/v1/issues/" + secret.issueId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawValue").value(nullValue()))
                .andExpect(jsonPath("$.data.transformedValue").value(nullValue()));

        long dataSource =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, :code, 'POSTGRES') returning id
                                """)
                        .param("code", "copy403-unc-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long table = resource(dataSource, schema() + ".unc_tbl");
        long mapping = objectMapping(table, unclassified, "code");
        propertyMapping(mapping, table, props.get("code"), column(table, "code", 1));
        propertyMapping(mapping, table, props.get("secret"), column(table, "secret_col", 2));
        long proposal =
                proposal(
                        "PROPERTY",
                        "REJECTED",
                        """
                        {"reason": "예: UNC-V", "items": [
                          {"locator": "%1$s.unc_tbl.code", "values": ["UNC-V"], "snippet": "UNC-V"}]}
                        """
                                .formatted(schema()));
        long restrictedProposal =
                proposal(
                        "PROPERTY",
                        "REJECTED",
                        """
                        {"reason": "예: UNC-SECRET", "items": [
                          {"locator": "%1$s.unc_tbl.secret_col", "values": ["UNC-SECRET"], "snippet": "UNC-SECRET"}]}
                        """
                                .formatted(schema()));

        mockMvc.perform(get("/api/v1/proposals/" + proposal).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence.items[0].values[0]").value("UNC-V"))
                .andExpect(jsonPath("$.data.evidence.reason").value("예: UNC-V"));
        String restricted =
                mockMvc.perform(get("/api/v1/proposals/" + restrictedProposal).cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.evidence.items[0].values").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(restricted).doesNotContain("UNC-SECRET");
    }

    @Test
    void viewerCannotUseStoredCopyApis() throws Exception {
        Cookie session = login(viewer);
        long proposal = proposalWithEvidence();
        for (String path :
                List.of(
                        "/api/v1/issues",
                        "/api/v1/issues/" + normal.issueId,
                        "/api/v1/issues/" + restricted.issueId + "/verifications",
                        "/api/v1/checks/runs/" + baselineRun + "/results",
                        "/api/v1/checks/runs/" + reverifyRun + "/comparison",
                        "/api/v1/proposals/" + proposal,
                        "/api/v1/proposals/" + proposal + "/options",
                        "/api/v1/proposals/" + proposal + "/revisions",
                        "/api/v1/proposals",
                        "/api/v1/analyses/1/proposals")) {
            String body =
                    mockMvc.perform(get(path).cookie(session))
                            .andExpect(status().isForbidden())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(body).doesNotContain(restricted.raw(), normal.raw(), "SECRET-V");
        }
    }

    /** 민감 값을 볼 수 있는 허용 행이면 값을 받고, 열람 기록에는 속성 이름만 남는다. 가려서 내보낸 응답은 남지 않는다. */
    @Test
    void recordsRevealedRestrictedValuesWithoutTheValue() throws Exception {
        Cookie session = login(admin);
        mockMvc.perform(get("/api/v1/issues/" + revealed.issueId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawValue").value(revealed.raw()));
        mockMvc.perform(get("/api/v1/issues/" + restricted.issueId).cookie(session))
                .andExpect(status().isOk());

        List<Map<String, Object>> logs = logsOf(admin);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst())
                .containsEntry("target_type", "issue")
                .containsEntry("target_id", revealed.issueId);
        assertThat((String) logs.getFirst().get("result"))
                .contains("ISSUE_DETAIL", revealed.sourceType + ".secret")
                .doesNotContain(revealed.raw(), revealed.transformed());

        mockMvc.perform(
                        get("/api/v1/checks/runs/" + baselineRun + "/results")
                                .param("linkMappingId", "" + revealed.id)
                                .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sampleRows[0].rawValue").value(revealed.raw()));
        assertThat(logsOf(admin)).hasSize(2);
        assertThat((String) logsOf(admin).getLast().get("result"))
                .contains("CHECK_RESULTS", "check_result")
                .doesNotContain(revealed.raw());
    }

    /** 문서 연결 근거는 항목 하나를 가리면 문서 본문·관찰·시각·이름·LLM 사유도 같이 가린다(AI 형식 api_format.py). */
    @Test
    void documentLinkEvidenceHidesDocumentTextWithAWithheldItem() throws Exception {
        mapEvidenceTables();
        String evidence =
                """
                {"reason": "문서 근거", "llmJudgement": "LLM-SECRET 맞음",
                 "items": [{"locator": "%s.hidden_tbl.code", "values": ["HIDDEN-V"], "snippet": "HIDDEN-V"}],
                 "originalText": "DOC-SECRET 원문", "documentLabel": "LABEL-SECRET",
                 "observations": [{"text": "OBS-SECRET", "kind": "MENTION"}],
                 "times": [{"text": "TIME-SECRET", "value": "2026-11-01"}],
                 "targetLabels": {"7": "TL-SECRET"}}
                """
                        .formatted(schema());
        long proposal = proposal("DOCUMENT_LINK", "REJECTED", evidence);

        String body =
                mockMvc.perform(get("/api/v1/proposals/" + proposal).cookie(login(admin)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.evidence.items[0].values").isEmpty())
                        .andExpect(jsonPath("$.data.evidence.originalText").value(WITHHELD_TEXT))
                        .andExpect(jsonPath("$.data.evidence.documentLabel").value(WITHHELD_TEXT))
                        .andExpect(jsonPath("$.data.evidence.llmJudgement").value(WITHHELD_TEXT))
                        .andExpect(
                                jsonPath("$.data.evidence.observations[0].text")
                                        .value(WITHHELD_TEXT))
                        .andExpect(
                                jsonPath("$.data.evidence.observations[0].kind").value("MENTION"))
                        .andExpect(jsonPath("$.data.evidence.times[0].text").value(WITHHELD_TEXT))
                        .andExpect(jsonPath("$.data.evidence.targetLabels.7").value(WITHHELD_TEXT))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body)
                .doesNotContain(
                        "HIDDEN-V",
                        "LLM-SECRET",
                        "DOC-SECRET",
                        "LABEL-SECRET",
                        "OBS-SECRET",
                        "TIME-SECRET",
                        "TL-SECRET");
    }

    /** 화면은 가린 근거를 받아 고친 뒤 통째로 개정 저장한다. 가린 자리는 저장된 원본으로 되돌리고, 사람이 고친 자리(reason)만 바꾼다. 선택지 근거도 같다. */
    @Test
    void revisionKeepsOriginalEvidenceBehindTheMask() throws Exception {
        mapEvidenceTables();
        long proposal = proposal("PROPERTY", "HELD", evidence());
        option(proposal, evidence());
        Cookie session = login(admin);

        Map<String, Object> shown =
                json.readValue(
                        mockMvc.perform(get("/api/v1/proposals/" + proposal).cookie(session))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        MAP);
        Map<String, Object> options =
                json.readValue(
                        mockMvc.perform(
                                        get("/api/v1/proposals/" + proposal + "/options")
                                                .cookie(session))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        MAP);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence =
                new java.util.LinkedHashMap<>(
                        (Map<String, Object>)
                                ((Map<String, Object>) shown.get("data")).get("evidence"));
        assertThat(evidence.toString()).doesNotContain("SECRET-V");
        evidence.put("reason", "사람이 고친 이유");
        @SuppressWarnings("unchecked")
        Map<String, Object> optionEvidence =
                (Map<String, Object>)
                        ((List<Map<String, Object>>) options.get("data"))
                                .getFirst()
                                .get("evidence");
        assertThat(optionEvidence.toString()).doesNotContain("SECRET-V");

        String request =
                json.writeValueAsString(
                        Map.of(
                                "baseRevision",
                                1,
                                "payload",
                                Map.of("code", "x"),
                                "evidence",
                                evidence,
                                "options",
                                List.of(
                                        Map.of(
                                                "payload",
                                                Map.of("code", "y"),
                                                "evidence",
                                                optionEvidence))));
        String token = UUID.randomUUID().toString();
        String response =
                mockMvc.perform(
                                post("/api/v1/proposals/" + proposal + "/revisions")
                                        .cookie(session, new Cookie("XSRF-TOKEN", token))
                                        .header("X-XSRF-TOKEN", token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(request))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        // 개정 응답도 조회와 같이 가린다. 되돌린 원본을 그대로 돌려주지 않는다(428)
        assertThat(response).contains("조회 범위상").doesNotContain("SECRET-V", "HIDDEN-V");

        String stored =
                jdbc.sql("select evidence::text from proposal_item where id = :id")
                        .param("id", proposal)
                        .query(String.class)
                        .single();
        assertThat(stored)
                .contains("SECRET-V", "HIDDEN-V", "SECRET-UPPER", "사람이 고친 이유", "예: SECRET-V")
                .doesNotContain("조회 범위상");
        String storedOption =
                jdbc.sql(
                                "select evidence::text from proposal_option where proposal_item_id = :id and revision = 2")
                        .param("id", proposal)
                        .query(String.class)
                        .single();
        assertThat(storedOption)
                .contains("SECRET-V", "HIDDEN-V", "불일치 예: SECRET-V")
                .doesNotContain("조회 범위상");
    }

    /**
     * 가린 근거 항목의 위치를 보이는 컬럼으로 바꾸거나 지워 저장하면, 되돌린 원문이 가려지지 않는 위치로 저장되어 응답에 나온다. 가린 근거가 있으면 근거 항목을 받은
     * 그대로 보내야 하고, 아니면 거절한다(384 보완).
     */
    @Test
    void revisionRejectsMovingOrUnlabelingWithheldEvidence() throws Exception {
        mapEvidenceTables();
        long proposal = proposal("PROPERTY", "HELD", evidence());
        Cookie session = login(admin);

        Map<String, Object> moved = shownEvidence(proposal, session);
        items(moved).getFirst().put("locator", schema() + ".tbl.code_col");
        assertRejected(revise(proposal, session, moved, List.of()), "\"field\":\"evidence\"");

        Map<String, Object> unlabeled = shownEvidence(proposal, session);
        items(unlabeled).getFirst().remove("locator");
        assertRejected(revise(proposal, session, unlabeled, List.of()), "\"field\":\"evidence\"");

        assertThat(storedRevision(proposal)).isEqualTo(1);
    }

    /** 가린 항목을 빼거나 순서를 바꾸면, 그 항목 때문에 가렸던 위쪽 설명(reason·holdReason)이 되돌려진 뒤 가려지지 않는다. 그래서 거절한다. */
    @Test
    void revisionRejectsRemovingOrReorderingWithheldEvidence() throws Exception {
        mapEvidenceTables();
        long proposal = proposal("PROPERTY", "HELD", evidence());
        Cookie session = login(admin);

        Map<String, Object> removed = shownEvidence(proposal, session);
        items(removed).removeFirst();
        assertRejected(revise(proposal, session, removed, List.of()), "\"field\":\"evidence\"");

        Map<String, Object> reordered = shownEvidence(proposal, session);
        List<Map<String, Object>> swapped = items(reordered);
        swapped.add(swapped.removeFirst());
        assertRejected(revise(proposal, session, reordered, List.of()), "\"field\":\"evidence\"");

        assertThat(storedRevision(proposal)).isEqualTo(1);
    }

    /** 선택지는 식별자 없이 순서로 맞춘다. 근거가 가려진 선택지의 순서를 바꿔 보내면 다른 선택지의 원문이 붙으므로 거절한다. */
    @Test
    void revisionRejectsReorderedOptionsWithWithheldEvidence() throws Exception {
        mapEvidenceTables();
        long proposal = proposal("PROPERTY", "HELD", evidence());
        option(proposal, evidence());
        jdbc.sql(
                        """
                        insert into proposal_option (proposal_item_id, revision, option_no, payload, evidence)
                        values (:id, 1, 2, '{"code": "z"}'::jsonb, cast(:evidence as jsonb))
                        """)
                .param("id", proposal)
                .param("evidence", evidence().replace("SECRET-V", "OTHER-V"))
                .update();
        Cookie session = login(admin);
        Map<String, Object> evidence = shownEvidence(proposal, session);
        Map<String, Object> options =
                json.readValue(
                        mockMvc.perform(
                                        get("/api/v1/proposals/" + proposal + "/options")
                                                .cookie(session))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shown = (List<Map<String, Object>>) options.get("data");
        List<Map<String, Object>> swapped =
                List.of(
                        Map.of(
                                "payload",
                                shown.get(1).get("payload"),
                                "evidence",
                                shown.get(1).get("evidence")),
                        Map.of(
                                "payload",
                                shown.get(0).get("payload"),
                                "evidence",
                                shown.get(0).get("evidence")));

        assertRejected(revise(proposal, session, evidence, swapped), "options[0].payload");

        assertThat(storedRevision(proposal)).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shownEvidence(long proposal, Cookie session) throws Exception {
        Map<String, Object> shown =
                json.readValue(
                        mockMvc.perform(get("/api/v1/proposals/" + proposal).cookie(session))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        MAP);
        Map<String, Object> evidence =
                new java.util.LinkedHashMap<>(
                        (Map<String, Object>)
                                ((Map<String, Object>) shown.get("data")).get("evidence"));
        List<Map<String, Object>> copies = new java.util.ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) evidence.get("items")) {
            copies.add(new java.util.LinkedHashMap<>(item));
        }
        evidence.put("items", copies);
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> evidence) {
        return (List<Map<String, Object>>) evidence.get("items");
    }

    private String revise(
            long proposal,
            Cookie session,
            Map<String, Object> evidence,
            List<Map<String, Object>> options)
            throws Exception {
        String token = UUID.randomUUID().toString();
        return mockMvc.perform(
                        post("/api/v1/proposals/" + proposal + "/revisions")
                                .cookie(session, new Cookie("XSRF-TOKEN", token))
                                .header("X-XSRF-TOKEN", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "baseRevision",
                                                        1,
                                                        "payload",
                                                        Map.of("code", "x"),
                                                        "evidence",
                                                        evidence,
                                                        "options",
                                                        options))))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** 거절 응답에 원문이 없고, 어느 자리 때문인지 알린다. */
    private static void assertRejected(String response, String field) {
        assertThat(response)
                .contains(field)
                .doesNotContain("SECRET-V", "HIDDEN-V", "SECRET-UPPER", "OTHER-V");
    }

    private int storedRevision(long proposal) {
        return jdbc.sql("select revision from proposal_item where id = :id")
                .param("id", proposal)
                .query(Integer.class)
                .single();
    }

    // ---- 데이터 ----

    private final class Rule {
        final long id;
        final String code;
        final String sourceType;
        long issueId;
        long objectId;

        Rule(long id, String code, String sourceType) {
            this.id = id;
            this.code = code;
            this.sourceType = sourceType;
        }

        String raw() {
            return "RAW-" + code;
        }

        String transformed() {
            return "TR-" + code;
        }

        String key() {
            return "KEY-" + code;
        }
    }

    private String schema() {
        return "copy403_" + suffix.toLowerCase();
    }

    private long type(String prefix, String group) {
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, '사본 범위 시험', :group) returning id
                        """)
                .param("code", prefix + "_" + suffix)
                .param("group", group)
                .query(Long.class)
                .single();
    }

    private String typeCode(long typeId) {
        return jdbc.sql("select code from object_type where id = :id")
                .param("id", typeId)
                .query(String.class)
                .single();
    }

    /** 일반 속성 code와 민감 속성 secret. secret의 값은 일반일 때 저장됐다고 본다(저장 뒤 민감으로 바뀐 경우). */
    private Map<String, Long> properties(long typeId) {
        long code = property(typeId, "code", "NORMAL");
        long secret = property(typeId, "secret", "RESTRICTED");
        return Map.of("code", code, "secret", secret);
    }

    private long property(long typeId, String code, String sensitivity) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity) returning id
                        """)
                .param("type", typeId)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .query(Long.class)
                .single();
    }

    /** 관계 유형·규칙 개정본·비교 조건 하나. 조건은 규칙과 같은 트랜잭션에서만 넣을 수 있어 한 문장으로 만든다. */
    private Rule rule(
            String name, long source, long target, long sourceProperty, long targetProperty) {
        String code = name + "_" + suffix;
        long id =
                jdbc.sql(
                                """
                                with relation as (
                                    insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                    values (:code, :code, :source, :target, 'MANY_TO_ONE', '사본 범위 시험') returning id
                                ), mapping as (
                                    insert into link_mapping (link_type_id, code, revision)
                                    select id, :code, 1 from relation returning id
                                ), condition as (
                                    insert into link_mapping_condition (
                                        link_mapping_id, source_property_id, target_property_id, sequence_no)
                                    select id, :sourceProperty, :targetProperty, 1 from mapping
                                )
                                select id from mapping
                                """)
                        .param("code", code)
                        .param("source", source)
                        .param("target", target)
                        .param("sourceProperty", sourceProperty)
                        .param("targetProperty", targetProperty)
                        .query(Long.class)
                        .single();
        return new Rule(id, code, typeCode(source));
    }

    /**
     * 원천 키 컬럼 매핑. SRC의 code·TGT의 code는 일반 속성에, SRC의 secret_key는 민감 속성에 매핑한다. 표본의 unmapped 키 컬럼은
     * 매핑하지 않는다.
     */
    private void mapKeyColumns(
            long source,
            long target,
            Map<String, Long> sourceProps,
            Map<String, Long> targetProps) {
        long dataSource =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, :code, 'POSTGRES') returning id
                                """)
                        .param("code", "copy403-key-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long sourceTable = resource(dataSource, schema() + ".src_key");
        long targetTable = resource(dataSource, schema() + ".tgt_key");
        long sourceMapping = objectMapping(sourceTable, source, "code");
        long targetMapping = objectMapping(targetTable, target, "code");
        propertyMapping(
                sourceMapping,
                sourceTable,
                sourceProps.get("code"),
                column(sourceTable, "code", 1));
        propertyMapping(
                sourceMapping,
                sourceTable,
                sourceProps.get("secret"),
                column(sourceTable, "secret_key", 2));
        propertyMapping(
                targetMapping,
                targetTable,
                targetProps.get("code"),
                column(targetTable, "code", 1));
    }

    private long run(String trigger, Long comparedTo) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key, compared_to_run_id,
                            requested_by, started_at, finished_at
                        )
                        values (:trigger, 'SUCCEEDED', :key, :comparedTo, :admin, now(), now())
                        returning id
                        """)
                .param("trigger", trigger)
                .param("key", "copy403-" + UUID.randomUUID())
                .param("comparedTo", comparedTo, java.sql.Types.BIGINT)
                .param("admin", admin)
                .query(Long.class)
                .single();
    }

    private long issue(Rule rule) {
        return jdbc.sql(
                        """
                        insert into issue (
                            issue_kind, status, dedupe_key, link_mapping_id, raw_value,
                            transformed_value, failure_reason, evidence, detected_check_run_id
                        )
                        values (
                            'UNMATCHED', 'NEW', :key, :rule, :raw, :transformed,
                            '조건 1(code)이 같은 도착이 없습니다.',
                            jsonb_build_object('ruleCode', cast(:code as text),
                                               'transformedValues', jsonb_build_object('1', cast(:transformed as text)),
                                               'causeConfirmed', false),
                            :run
                        )
                        returning id
                        """)
                .param("key", "UNMATCHED:" + rule.code)
                .param("rule", rule.id)
                .param("raw", rule.raw())
                .param("transformed", rule.transformed())
                .param("code", rule.code)
                .param("run", baselineRun)
                .query(Long.class)
                .single();
    }

    private void checkResult(long runId, Rule rule, long objectId, String classification) {
        jdbc.sql(
                        """
                        insert into check_result (
                            check_run_id, link_mapping_id, total_count, matched_count,
                            unmatched_count, duplicate_count, violation_count, sample_rows
                        )
                        values (
                            :run, :rule, 1, :matched, :unmatched, 0, 0,
                            jsonb_build_array(jsonb_build_object(
                                'objectInstanceId', cast(:object as bigint),
                                'sourceResourceId', 1,
                                'sourceKey', jsonb_build_object('code', cast(:key as text),
                                                             'secret_key', 'SK-' || cast(:key as text),
                                                             'unmapped', 'UM-' || cast(:key as text)),
                                'classification', cast(:classification as text),
                                'rawValue', cast(:raw as text),
                                'transformedValue', cast(:transformed as text),
                                'reason', '조건 1(code)이 같은 도착이 없습니다.',
                                'targetObjectIds', '[]'::jsonb))
                        )
                        """)
                .param("run", runId)
                .param("rule", rule.id)
                .param("matched", "MATCHED".equals(classification) ? 1 : 0)
                .param("unmatched", "MATCHED".equals(classification) ? 0 : 1)
                .param("object", objectId)
                .param("key", rule.key())
                .param("classification", classification)
                .param("raw", rule.raw())
                .param("transformed", rule.transformed())
                .update();
    }

    private void verification(long issueId, long runId, Rule rule) {
        jdbc.sql(
                        """
                        insert into issue_verification (issue_id, check_run_id, outcome, detail)
                        values (:issue, :run, 'FAILED', jsonb_build_object(
                            'linkMappingId', cast(:rule as bigint),
                            'rawValue', cast(:raw as text),
                            'transformedValue', cast(:transformed as text),
                            'targetFound', false,
                            'classification', 'UNMATCHED',
                            'reason', '조건 1(code)이 같은 도착이 없습니다.'))
                        """)
                .param("issue", issueId)
                .param("run", runId)
                .param("rule", rule.id)
                .param("raw", rule.raw())
                .param("transformed", rule.transformed())
                .update();
    }

    /**
     * 있는 관계 유형에 대한 연결 규칙 후보와 그 시험 결과. 표본은 후보 시험이 저장 때 가리지 않은 모양이다. 다른 시험의 목록 건수에 섞이지 않게 반려 상태로 둔다.
     */
    private long candidate(Rule rule, String property) {
        String linkTypeCode = rule.code;
        String payload =
                """
                {"linkTypeCode": "%s", "code": "CAND_%s",
                 "conditions": [{"sourceProperty": "%s", "targetProperty": "%s", "operator": "EQ"}]}
                """
                        .formatted(linkTypeCode, rule.code, property, property);
        long proposal =
                jdbc.sql(
                                """
                                with added as (
                                    insert into proposal_item (origin, item_kind, payload, review_status, created_by)
                                    values ('MANUAL', 'LINK_RULE', cast(:payload as jsonb), 'REJECTED', :admin) returning id
                                )
                                insert into proposal_revision (proposal_item_id, revision, payload, created_by)
                                select id, 1, cast(:payload as jsonb), :admin from added
                                returning proposal_item_id
                                """)
                        .param("payload", payload)
                        .param("admin", admin)
                        .query(Long.class)
                        .single();
        long runId =
                jdbc.sql(
                                """
                                insert into check_run (
                                    trigger_kind, status, scope, work_key, started_at, finished_at)
                                values ('CANDIDATE', 'SUCCEEDED',
                                    jsonb_build_object('proposalItems', jsonb_build_array(jsonb_build_object(
                                        'proposalItemId', cast(:proposal as bigint), 'revision', 1))),
                                    :key, now(), now())
                                returning id
                                """)
                        .param("proposal", proposal)
                        .param("key", "copy403-candidate-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into check_result (
                            check_run_id, proposal_item_id, proposal_item_revision, total_count,
                            matched_count, unmatched_count, duplicate_count, violation_count, sample_rows
                        )
                        values (
                            :run, :proposal, 1, 1, 0, 0, 1, 0,
                            '[{"sourceResourceId": 1, "sourceKey": {"code": "CAND-S"},
                               "classification": "DUPLICATE", "rawValue": "CAND-RAW",
                               "transformedValue": "CAND-RAW", "reason": "대응한 도착 원천 키가 중복되었습니다.",
                               "targetKey": {"code": "CAND-T"}}]'::jsonb
                        )
                        """)
                .param("run", runId)
                .param("proposal", proposal)
                .update();
        return runId;
    }

    /**
     * 출발 유형(SRC)에 매핑된 표의 민감 컬럼·일반 컬럼, 허용 행이 없는 그룹의 유형(HID)에 매핑된 표의 컬럼을 가리키는 근거 세 개를 가진 제안. 근거 값은 원천
     * 정책상 일반 컬럼이라 105 수신 때는 가려지지 않았다.
     */
    private long proposalWithEvidence() {
        mapEvidenceTables();
        return proposal("PROPERTY", "REJECTED", evidence());
    }

    /** 근거 위치가 가리키는 표·컬럼을 SRC(일반·민감 컬럼)와 HID(허용 행이 없는 그룹)에 매핑한다. */
    private void mapEvidenceTables() {
        long sourceType = typeId("SRC_" + suffix);
        long hiddenType = typeId("HID_" + suffix);
        long dataSource =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, :code, 'POSTGRES') returning id
                                """)
                        .param("code", "copy403-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        long table = resource(dataSource, schema() + ".tbl");
        long hiddenTable = resource(dataSource, schema() + ".hidden_tbl");
        long secretColumn = column(table, "secret_col", 1);
        long codeColumn = column(table, "code_col", 2);
        column(hiddenTable, "code", 1);
        long mapping = objectMapping(table, sourceType, "code_col");
        objectMapping(hiddenTable, hiddenType, "code");
        propertyMapping(mapping, table, propertyId(sourceType, "secret"), secretColumn);
        propertyMapping(mapping, table, propertyId(sourceType, "code"), codeColumn);
    }

    private String evidence() {
        return """
                {"reason": "불일치 예: SECRET-V, CODE-V", "holdReason": "예: SECRET-V", "items": [
                  {"locator": "%1$s.tbl.secret_col", "values": ["SECRET-V"], "snippet": "SECRET-V 외 2건"},
                  {"locator": "%1$s.tbl.code_col", "values": ["CODE-V"], "snippet": "CODE-V"},
                  {"locator": "%1$s.hidden_tbl.code", "values": ["HIDDEN-V"], "snippet": "HIDDEN-V"},
                  {"locator": "%1$s.TBL.SECRET_COL", "values": ["SECRET-UPPER"], "snippet": "SECRET-UPPER"}
                ]}
                """
                .formatted(schema());
    }

    /** 수동 제안 하나와 그 개정본 1. 다른 시험의 목록 건수에 섞이지 않게 PENDING이 아닌 상태로 둔다. */
    private long proposal(String kind, String status, String evidence) {
        return jdbc.sql(
                        """
                        with added as (
                            insert into proposal_item (origin, item_kind, payload, evidence, review_status, created_by)
                            values ('MANUAL', :kind, '{"code": "x"}'::jsonb, cast(:evidence as jsonb), :status, :admin)
                            returning id
                        )
                        insert into proposal_revision (proposal_item_id, revision, payload, evidence, created_by)
                        select id, 1, '{"code": "x"}'::jsonb, cast(:evidence as jsonb), :admin from added
                        returning proposal_item_id
                        """)
                .param("kind", kind)
                .param("status", status)
                .param("evidence", evidence)
                .param("admin", admin)
                .query(Long.class)
                .single();
    }

    private void option(long proposalId, String evidence) {
        jdbc.sql(
                        """
                        insert into proposal_option (proposal_item_id, revision, option_no, payload, evidence)
                        values (:id, 1, 1, '{"code": "y"}'::jsonb, cast(:evidence as jsonb))
                        """)
                .param("id", proposalId)
                .param("evidence", evidence)
                .update();
    }

    private long typeId(String code) {
        return jdbc.sql("select id from object_type where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long propertyId(long typeId, String code) {
        return jdbc.sql(
                        "select id from property_definition where object_type_id = :type and code = :code")
                .param("type", typeId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long resource(long dataSource, String key) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind)
                        values (:source, :key, 'TABLE') returning id
                        """)
                .param("source", dataSource)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long column(long resource, String name, int position) {
        return jdbc.sql(
                        """
                        insert into source_column (
                            source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                        values (:resource, :name, 'varchar', true, :position) returning id
                        """)
                .param("resource", resource)
                .param("name", name)
                .param("position", position)
                .query(Long.class)
                .single();
    }

    private long objectMapping(long resource, long type, String keyColumn) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:resource, :type, jsonb_build_array(cast(:key as text))) returning id
                        """)
                .param("resource", resource)
                .param("type", type)
                .param("key", keyColumn)
                .query(Long.class)
                .single();
    }

    private void propertyMapping(long mapping, long resource, long property, long column) {
        jdbc.sql(
                        """
                        insert into property_source_mapping (
                            source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column)
                        """)
                .param("mapping", mapping)
                .param("resource", resource)
                .param("property", property)
                .param("column", column)
                .update();
    }

    private List<Map<String, Object>> logsOf(long accountId) {
        return jdbc.sql(
                        """
                        select id, target_type, target_id, result::text as result
                        from action_execution
                        where actor_id = :actor and event_kind = 'RESTRICTED_VIEW'
                        order by id
                        """)
                .param("actor", accountId)
                .query()
                .listOfRows();
    }

    private void allow(String role, String group, boolean restrictedValues) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        values ((select id from role where code = :role), :group, :restricted)
                        """)
                .param("role", role)
                .param("group", group)
                .param("restricted", restrictedValues)
                .update();
    }

    private long account(String role) {
        String loginId = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                        values (:loginId, '사본 범위', :hash, (select id from role where code = :role), false)
                        returning id
                        """)
                .param("loginId", loginId)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private Cookie login(long accountId) throws Exception {
        String loginId =
                jdbc.sql("select login_id from account where id = :id")
                        .param("id", accountId)
                        .query(String.class)
                        .single();
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"loginId\": \"%s\", \"password\": \"%s\"}"
                                                .formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
    }
}
