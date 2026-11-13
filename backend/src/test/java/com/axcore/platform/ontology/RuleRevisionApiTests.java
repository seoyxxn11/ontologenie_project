package com.axcore.platform.ontology;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.global.security.LoginAccount;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 연결 규칙 개정 이력(API 046)·개정본 상세(API 047)를 실제 보안 설정과 DB로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class RuleRevisionApiTests {

    private static final String RULES = "/api/v1/ontology/rules";
    private static final String PATH = RULES + "/{code}/revisions";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private CheckRunTestFixtures fixtures;
    private String code;
    private long linkType;
    private long woEqCode;
    private long eqCode;
    private long woLine;
    private long eqLine;
    private long accountId;

    @BeforeEach
    void setUp() {
        fixtures = new CheckRunTestFixtures(jdbc);
        accountId = fixtures.insertAdmin();
        long woType = fixtures.insertObjectType("WO");
        long eqType = fixtures.insertObjectType("EQ");
        woEqCode = fixtures.insertProperty(woType, "eq_code");
        woLine = fixtures.insertProperty(woType, "line");
        eqCode = fixtures.insertProperty(eqType, "code");
        eqLine = fixtures.insertProperty(eqType, "line");
        linkType = fixtures.insertLinkType(woType, eqType, "MANY_TO_ONE");
        code = "WO_EQ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void listsRevisionsInOrderWithConditions() throws Exception {
        long first = revision(1, "SEED", "작업지시의 설비 코드로 설비 연결(초판)", "{}");
        retire(first);
        long second =
                revision(
                        2,
                        "MANUAL",
                        "작업지시의 설비 코드와 라인으로 설비 연결",
                        """
                        {"line": "L1"}
                        """);

        mvc.perform(get(PATH, code).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", contains((int) first, (int) second)))
                .andExpect(jsonPath("$.data[*].revision", contains(1, 2)))
                .andExpect(jsonPath("$.data[*].status", contains("RETIRED", "ACTIVE")))
                .andExpect(jsonPath("$.data[0].code").value(code))
                .andExpect(jsonPath("$.data[0].linkTypeId").value((int) linkType))
                .andExpect(jsonPath("$.data[0].linkTypeName").value("검사 테스트 관계"))
                .andExpect(jsonPath("$.data[0].retiredAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].origin").value("SEED"))
                .andExpect(jsonPath("$.data[0].proposalItemId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].conditions.length()").value(1))
                .andExpect(jsonPath("$.data[0].conditions[0].sequenceNo").value(1))
                .andExpect(
                        jsonPath("$.data[0].conditions[0].sourcePropertyId").value((int) woEqCode))
                .andExpect(jsonPath("$.data[0].conditions[0].sourcePropertyName").value("eq_code"))
                .andExpect(jsonPath("$.data[0].conditions[0].targetPropertyId").value((int) eqCode))
                .andExpect(jsonPath("$.data[0].conditions[0].targetPropertyName").value("code"))
                .andExpect(jsonPath("$.data[0].conditions[0].operator").value("EQ"))
                .andExpect(jsonPath("$.data[0].conditions[0].sourceTransforms.length()").value(0))
                .andExpect(
                        jsonPath("$.data[0].conditions[0].targetTransforms[0].op").value("UPPER"))
                .andExpect(jsonPath("$.data[1].retiredAt").value(nullValue()))
                .andExpect(jsonPath("$.data[1].sourceFilter.line").value("L1"))
                .andExpect(jsonPath("$.data[1].targetFilter").isMap())
                .andExpect(jsonPath("$.data[1].description").value("작업지시의 설비 코드와 라인으로 설비 연결"))
                .andExpect(jsonPath("$.data[1].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].createdBy").value((int) accountId))
                .andExpect(jsonPath("$.data[1].conditions[*].sequenceNo", contains(1, 2)))
                .andExpect(jsonPath("$.data[1].conditions[1].sourcePropertyId").value((int) woLine))
                .andExpect(jsonPath("$.data[1].conditions[1].sourcePropertyName").value("line"))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));

        mvc.perform(get(PATH, code).param("size", "1").param("page", "1").with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].revision", contains(2)))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(2));
    }

    /** 386: 속성·관계 유형 이름은 개정 당시가 아니라 지금 이름을 보여 준다. */
    @Test
    void showsOneRevision() throws Exception {
        long first = revision(1, "SEED", null, "{}");
        retire(first);
        revision(2, "MANUAL", "둘째", "{}");
        jdbc.sql("update property_definition set name = '설비 코드' where id = :id")
                .param("id", eqCode)
                .update();
        jdbc.sql("update link_type set name = '작업지시 설비' where id = :id")
                .param("id", linkType)
                .update();

        mvc.perform(get(PATH + "/{revision}", code, 1).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) first))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.status").value("RETIRED"))
                .andExpect(jsonPath("$.data.description").value(nullValue()))
                .andExpect(jsonPath("$.data.linkTypeName").value("작업지시 설비"))
                .andExpect(jsonPath("$.data.conditions[0].sourcePropertyName").value("eq_code"))
                .andExpect(jsonPath("$.data.conditions[0].targetPropertyName").value("설비 코드"))
                .andExpect(jsonPath("$.data.conditions.length()").value(1));
        mvc.perform(get(PATH + "/{revision}", code, 2).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.conditions.length()").value(2));
    }

    @Test
    void rejectsUnknownCodeRevisionAndInvalidInput() throws Exception {
        revision(1, "SEED", null, "{}");

        mvc.perform(get(PATH, "NO_SUCH_RULE_" + UUID.randomUUID()).with(actor("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("link_mapping"));
        mvc.perform(get(PATH + "/{revision}", code, 9).with(actor("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.details.revision").value(9));
        mvc.perform(get(PATH + "/{revision}", code, 3_000_000_000L).with(actor("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/{revision}", code, 0).with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH, code).param("size", "101").with(actor("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("size"));
        mvc.perform(get(PATH, code).param("page", "-1").with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsTheLatestRevisionOfEachRuleWithFilters() throws Exception {
        // 관계 유형마다 반영 중인 규칙은 하나다(V17). 다른 코드의 규칙은 폐기된 뒤에 같은 관계 유형에 남는다.
        long first = revision(1, "SEED", "초판", "{}");
        retire(first);
        String retiredCode = code;
        code = "WO_EQ_OLD_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String onlyRetired = code;
        retire(revision(1, "SEED", "대체 없이 폐기된 규칙", "{}"));
        code = retiredCode;
        revision(2, "MANUAL", "둘째", "{}");

        mvc.perform(get(RULES).param("linkTypeId", String.valueOf(linkType)).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath(
                                "$.data[*].code",
                                contains(
                                        retiredCode.compareTo(onlyRetired) < 0
                                                ? new String[] {retiredCode, onlyRetired}
                                                : new String[] {onlyRetired, retiredCode})))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.code == '" + retiredCode + "')].revision", contains(2)))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.code == '" + retiredCode + "')].status",
                                contains("ACTIVE")))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.code == '" + retiredCode + "')].description",
                                contains("둘째")))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.code == '" + retiredCode + "')].linkTypeName",
                                contains("검사 테스트 관계")))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.code == '" + onlyRetired + "')].status",
                                contains("RETIRED")))
                .andExpect(jsonPath("$.meta").doesNotExist());
        mvc.perform(
                        get(RULES)
                                .param("linkTypeId", String.valueOf(linkType))
                                .param("status", "ACTIVE")
                                .with(actor("ADMIN")))
                .andExpect(jsonPath("$.data[*].code", contains(retiredCode)));
        mvc.perform(
                        get(RULES)
                                .param("linkTypeId", String.valueOf(linkType))
                                .param("status", "RETIRED")
                                .with(actor("ADMIN")))
                .andExpect(jsonPath("$.data[*].code", contains(onlyRetired)));
        mvc.perform(get(RULES).param("linkTypeId", "0").with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(RULES).param("status", "UNKNOWN").with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(RULES).with(actor("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(RULES)).andExpect(status().isUnauthorized());
    }

    @Test
    void allowsOnlyAdmin() throws Exception {
        revision(1, "SEED", null, "{}");

        mvc.perform(get(PATH, code)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH, code).with(actor("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));
        mvc.perform(get(PATH + "/{revision}", code, 1).with(actor("VIEWER")))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor actor(String role) {
        return authentication(new LoginAccount(1L, "tester", role, false).toAuthentication());
    }

    /** 개정본과 비교 항목을 한 트랜잭션에서 만든다(비교 항목은 개정본을 만든 트랜잭션에서만 넣을 수 있다). 2판부터 라인 비교를 더한다. */
    private long revision(int revision, String origin, String description, String sourceFilter) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        tx -> {
                            long id =
                                    jdbc.sql(
                                                    """
                                                    insert into link_mapping (link_type_id, code, revision, source_filter,
                                                                              description, origin, created_by)
                                                    values (:linkType, :code, :revision, cast(:filter as jsonb),
                                                            :description, :origin, :createdBy)
                                                    returning id
                                                    """)
                                            .param("linkType", linkType)
                                            .param("code", code)
                                            .param("revision", revision)
                                            .param("filter", sourceFilter)
                                            .param("description", description)
                                            .param("origin", origin)
                                            .param("createdBy", accountId)
                                            .query(Long.class)
                                            .single();
                            fixtures.insertCondition(
                                    id,
                                    1,
                                    woEqCode,
                                    eqCode,
                                    "[]",
                                    "[{\"op\": \"UPPER\", \"arg\": null}]");
                            if (revision > 1) {
                                fixtures.insertCondition(id, 2, woLine, eqLine, "[]", "[]");
                            }
                            return id;
                        });
    }

    private void retire(long id) {
        jdbc.sql("update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                .param("id", id)
                .update();
    }
}
