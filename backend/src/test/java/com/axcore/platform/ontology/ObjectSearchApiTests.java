package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ObjectSearchApiTests {

    private static final String PATH = "/api/v1/objects";
    private static final String TYPES_PATH = "/api/v1/objects/types";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    /** 민감 값을 보여 준 응답은 열람 기록(394)을 남기고 그 기록은 실제 계정을 가리켜야 해서, 테스트 사용자를 계정 표에 둔다. */
    private long testerId;

    @BeforeEach
    void createTester() {
        testerId =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                                values (:loginId, 'tester', 'not-used', (select id from role where code = 'VIEWER'), false)
                                returning id
                                """)
                        .param("loginId", "tester-" + java.util.UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Autowired ObjectProvider<ObjectEvidenceQuery> evidenceQuery;
    @Autowired ObjectProvider<ObjectSearchQuery> searchQuery;

    @Test
    void combinesPropertyValuesAndDatesBeforeCountingAndPagination() throws Exception {
        String tag = UUID.randomUUID().toString();
        long visible = type(tag, tag);
        long hidden = type(tag + "-hidden", tag + "-private");
        allowViewer(tag);
        for (long typeId : new long[] {visible, hidden}) {
            property(typeId, "state", "STRING", "NORMAL", null);
            property(typeId, "line", "STRING", "NORMAL", null);
            property(typeId, "planned_start", "DATE", "NORMAL", null);
        }
        long first =
                object(
                        visible,
                        tag + " first",
                        """
                {"state":"진행","line":"1라인","planned_start":"2026-10-01"}
                """);
        long last =
                object(
                        visible,
                        tag,
                        """
                {"state":"진행","line":"1라인","planned_start":"2026-10-31"}
                """);
        object(
                visible,
                tag + " other state",
                """
                {"state":"완료","line":"1라인","planned_start":"2026-10-15"}
                """);
        object(
                visible,
                tag + " other line",
                """
                {"state":"진행","line":"2라인","planned_start":"2026-10-15"}
                """);
        object(
                visible,
                tag + " outside",
                """
                {"state":"진행","line":"1라인","planned_start":"2026-11-01"}
                """);
        object(
                hidden,
                tag + " hidden",
                """
                {"state":"진행","line":"1라인","planned_start":"2026-10-01"}
                """);
        for (int page = 0; page < 2; page++) {
            mvc.perform(
                            get(PATH)
                                    .param("q", tag)
                                    .param("objectTypeId", Long.toString(visible))
                                    .param("propertyValues[state]", "진행")
                                    .param("propertyValues[line]", "1라인")
                                    .param("dateProperty", "planned_start")
                                    .param("from", "2026-10-01")
                                    .param("to", "2026-10-31")
                                    .param("page", Integer.toString(page))
                                    .param("size", "1")
                                    .with(actor("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value((int) (page == 0 ? last : first)))
                    .andExpect(jsonPath("$.meta.totalElements").value(2))
                    .andExpect(jsonPath("$.meta.hasNext").value(page == 0));
        }
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(visible))
                                .param("dateProperty", "planned_start")
                                .param("from", "2026-11-01")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(visible))
                                .param("dateProperty", "planned_start")
                                .param("to", "2026-10-01")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) first))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void comparesDatetimesByInstantAndIgnoresMissingOrMalformedStoredDates() throws Exception {
        String tag = UUID.randomUUID().toString();
        long typeId = type(tag, null);
        property(typeId, "started_at", "DATETIME", "NORMAL", null);
        long first = object(typeId, "KST", "{\"started_at\":\"2026-10-01T09:00:00+09:00\"}");
        object(typeId, "UTC", "{\"started_at\":\"2026-10-01T00:00:00Z\"}");
        object(typeId, "earlier", "{\"started_at\":\"2026-09-30T23:59:59Z\"}");
        object(typeId, "missing", "{}");
        object(typeId, "null", "{\"started_at\":null}");
        object(typeId, "malformed", "{\"started_at\":\"not-a-date\"}");
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("dateProperty", "started_at")
                                .param("from", "2026-10-01T09:00:00+09:00")
                                .param("to", "2026-10-01T00:00:00Z")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) first))
                .andExpect(jsonPath("$.meta.totalElements").value(2));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("propertyValues[started_at]", "2026-10-01T09:00:00+09:00")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(2));
    }

    @Test
    void comparesScalarValuesUsingTheirDefinedTypesAndBindsLiteralText() throws Exception {
        String tag = UUID.randomUUID().toString();
        long typeId = type(tag, null);
        property(typeId, "count", "INTEGER", "NORMAL", null);
        property(typeId, "cost", "DECIMAL", "NORMAL", null);
        property(typeId, "enabled", "BOOLEAN", "NORMAL", null);
        property(typeId, "time", "TIME", "NORMAL", null);
        property(typeId, "text", "STRING", "NORMAL", null);
        long match =
                object(
                        typeId,
                        "literal",
                        """
                {"count":2,"cost":"1200.00","enabled":true,"time":"09:00:00","text":"' OR true --"}
                """);
        object(
                typeId,
                "different",
                """
                {"count":2,"cost":1200,"enabled":false,"time":"09:00:00","text":"other"}
                """);
        object(typeId, "malformed number", "{\"count\":\"bad\",\"cost\":null}");
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("propertyValues[count]", "2")
                                .param("propertyValues[cost]", "1200")
                                .param("propertyValues[enabled]", "true")
                                .param("propertyValues[time]", "09:00")
                                .param("propertyValues[text]", "' OR true --")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) match))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("propertyValues[cost]", "1200.0")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(2));
    }

    @Test
    void rejectsRestrictedFiltersBeforeLookingAtRowsAndAllowsAuthorizedActors() throws Exception {
        String tag = UUID.randomUUID().toString();
        long typeId = type(tag, tag);
        allowViewer(tag);
        property(typeId, "cost", "INTEGER", "RESTRICTED", null);
        property(typeId, "paid_on", "DATE", "RESTRICTED", null);
        object(typeId, tag, "{\"cost\":1200,\"paid_on\":\"2026-10-01\"}");
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("q", "no matches")
                                .param("propertyValues[cost]", "1200")
                                .with(actor("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.field").value("propertyValues[cost]"));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("dateProperty", "paid_on")
                                .param("from", "2026-10-01")
                                .with(actor("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("propertyValues[cost]", "1200")
                                .with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        jdbc.sql(
                        "update role_data_scope set can_view_restricted = true where access_group = :group")
                .param("group", tag)
                .update();
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("propertyValues[cost]", "1200")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void matchesStoredTextThatDiffersOnlyInSpacingWhenItIsTheOnlyOne() throws Exception {
        String tag = UUID.randomUUID().toString();
        long single = type(tag, null);
        property(single, "state", "STRING", "NORMAL", null);
        object(single, tag + " a", "{\"state\":\"정비중\"}");
        object(single, tag + " b", "{\"state\":\"정비중\"}");
        object(single, tag + " c", "{\"state\":\"가동\"}");
        for (String asked : new String[] {"정비 중", " 정비중 ", "정비중"}) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(single))
                                    .param("propertyValues[state]", asked)
                                    .with(actor("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.totalElements").value(2));
        }

        // 똑같은 저장 값이 있으면 공백만 다른 값은 섞지 않는다.
        long exact = type(tag + "-exact", null);
        property(exact, "state", "STRING", "NORMAL", null);
        long spaced = object(exact, tag + " spaced", "{\"state\":\"정비 중\"}");
        long compact = object(exact, tag + " compact", "{\"state\":\"정비중\"}");
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(exact))
                                .param("propertyValues[state]", "정비 중")
                                .with(actor("ADMIN")))
                .andExpect(jsonPath("$.data[*].id").value(contains((int) spaced)));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(exact))
                                .param("propertyValues[state]", "정비중")
                                .with(actor("ADMIN")))
                .andExpect(jsonPath("$.data[*].id").value(contains((int) compact)));

        // 공백만 다른 저장 값이 여럿이면 어느 것인지 정할 수 없어 맞추지 않는다.
        long ambiguous = type(tag + "-ambiguous", null);
        property(ambiguous, "state", "STRING", "NORMAL", null);
        object(ambiguous, tag + " left", "{\"state\":\"정 비중\"}");
        object(ambiguous, tag + " right", "{\"state\":\"정비 중\"}");
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(ambiguous))
                                .param("propertyValues[state]", "정비중")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    void periodPropertyErrorsListPropertiesTheActorCanSearchByPeriod() throws Exception {
        String tag = UUID.randomUUID().toString();
        long typeId = type(tag, tag);
        allowViewer(tag);
        property(typeId, "state", "STRING", "NORMAL", null);
        property(typeId, "planned_start", "DATE", "NORMAL", null);
        property(typeId, "started_at", "DATETIME", "NORMAL", null);
        property(typeId, "paid_on", "DATE", "RESTRICTED", null);
        for (String dateProperty : new String[] {"", "unknown", "state"}) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param("dateProperty", dateProperty)
                                    .param("from", "2026-10-01")
                                    .with(actor("VIEWER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.field").value("dateProperty"))
                    .andExpect(
                            jsonPath("$.error.details.reason")
                                    .value(
                                            containsString(
                                                    "기간으로 검색할 수 있는 속성: planned_start(planned_start),"
                                                            + " started_at(started_at)")))
                    // 볼 수 없는 민감 속성은 이름도 알려 주지 않는다.
                    .andExpect(
                            jsonPath("$.error.details.reason")
                                    .value(not(containsString("paid_on"))));
        }
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("from", "2026-10-01")
                                .with(actor("PLANNER")))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.details.reason")
                                .value(containsString("paid_on(paid_on)")));

        long noPeriod = type(tag + "-no-period", null);
        property(noPeriod, "state", "STRING", "NORMAL", null);
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(noPeriod))
                                .param("dateProperty", "state")
                                .param("from", "2026-10-01")
                                .with(actor("PLANNER")))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.details.reason")
                                .value(containsString("이 유형에는 기간으로 검색할 수 있는 속성이 없습니다.")));
    }

    @Test
    void rejectsUnknownPropertiesInvalidValuesAndIncompleteOrReversedPeriods() throws Exception {
        String tag = UUID.randomUUID().toString();
        long typeId = type(tag, null);
        property(typeId, "state", "STRING", "NORMAL", null);
        property(typeId, "count", "INTEGER", "NORMAL", null);
        property(typeId, "day", "DATE", "NORMAL", null);
        property(typeId, "timestamp", "DATETIME", "NORMAL", null);
        property(typeId, "flag", "BOOLEAN", "NORMAL", null);
        property(typeId, "payload", "JSON", "NORMAL", null);
        mvc.perform(get(PATH).param("propertyValues[state]", "진행").with(actor("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("objectTypeId"));
        for (Map.Entry<String, String> value :
                Map.of(
                                "unknown",
                                "a",
                                "count",
                                "1.5",
                                "day",
                                "2026-02-30",
                                "timestamp",
                                "2026-10-01T09:00:00",
                                "flag",
                                "yes",
                                "payload",
                                "{}")
                        .entrySet()) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param(
                                            "propertyValues[" + value.getKey() + "]",
                                            value.getValue())
                                    .with(actor("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        for (String dateProperty : new String[] {"unknown", "state", ""}) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param("dateProperty", dateProperty)
                                    .param("from", "2026-10-01")
                                    .with(actor("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        for (String value : new String[] {"0000-01-01", "-0001-01-01", "+999999999-01-01"}) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param("propertyValues[day]", value)
                                    .with(actor("ADMIN")))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param("dateProperty", "day")
                                    .param("from", value)
                                    .with(actor("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        for (String value :
                new String[] {
                    "0000-01-01T00:00:00Z",
                    "+999999999-01-01T00:00:00Z",
                    "0001-01-01T00:00:00+09:00",
                    "+999999999-12-31T23:59:59-18:00"
                }) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param("propertyValues[timestamp]", value)
                                    .with(actor("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("from", "2026-10-01")
                                .with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("dateProperty", "day")
                                .with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("dateProperty", "day")
                                .param("from", "2026-11-01")
                                .param("to", "2026-10-01")
                                .with(actor("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("to"));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(typeId))
                                .param("dateProperty", "timestamp")
                                .param("from", "2026-10-01T10:00:00Z")
                                .param("to", "2026-10-01T09:00:00Z")
                                .with(actor("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersDoNotRevealPropertiesOfHiddenOrNonexistentTypes() throws Exception {
        String tag = UUID.randomUUID().toString();
        long hidden = type(tag, tag + "-private");
        property(hidden, "secret", "INTEGER", "RESTRICTED", null);
        long visible = type(tag + "-visible", tag);
        allowViewer(tag);
        for (long typeId : new long[] {hidden, Long.MAX_VALUE}) {
            mvc.perform(
                            get(PATH)
                                    .param("objectTypeId", Long.toString(typeId))
                                    .param("propertyValues[secret]", "1")
                                    .with(actor("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.meta.totalElements").value(0));
        }
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(visible))
                                .param("propertyValues[secret]", "1")
                                .with(actor("VIEWER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void turnsOffJitOnlyWhileSearchingWithAKeyword() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, null);
        object(type, tag, "{}");
        jdbc.sql("set local jit = on").update();
        // 검색 결과에 조회 시점의 PostgreSQL 설정을 넣어 실제 조회 연결에서 읽는다.
        jdbc.sql(
                        """
                        create temporary view object_instance as
                        select id, object_type_id, source_mapping_id, source_key, display_name,
                               properties || jsonb_build_object('test_jit', current_setting('jit'))
                                   as properties,
                               status, first_collected_at, last_synced_at
                        from platform.object_instance
                        """)
                .update();

        mvc.perform(get(PATH).param("q", tag).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].properties.test_jit").value("off"));

        mvc.perform(get(PATH).param("objectTypeId", Long.toString(type)).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].properties.test_jit").value("on"));
    }

    @Test
    void scopesListAndCountBeforePaginationAndMasksOnlyRestrictedProperties() throws Exception {
        String tag = UUID.randomUUID().toString();
        long hidden = type(tag + "-hidden", tag + "-private");
        long ungrouped = type(tag + "-ungrouped", null);
        long visible = type(tag + "-visible", tag);
        allowViewer(tag);
        property(visible, "cost", "INTEGER", "RESTRICTED", null);
        property(visible, "effective_at", "DATETIME", "NORMAL", "AS_OF");
        object(hidden, tag + " press hidden", "{}");
        object(ungrouped, tag + " press ungrouped", "{}");
        long first =
                object(
                        visible,
                        tag + " Press",
                        """
                        {"cost":1200,"note":null,"effective_at":"2026-11-01T09:30:00+09:00"}
                        """);
        object(visible, tag + " press second", "{}");

        mvc.perform(get(PATH).param("q", tag + " press").param("size", "1").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) first))
                .andExpect(jsonPath("$.data[0].objectTypeName").value(tag + "-visible"))
                .andExpect(jsonPath("$.data[0].properties.cost").value(nullValue()))
                .andExpect(jsonPath("$.data[0].properties.note").value(nullValue()))
                .andExpect(jsonPath("$.data[0].restrictedPropertyCodes[0]").value("cost"))
                .andExpect(jsonPath("$.data[0].restrictedPropertyCodes.length()").value(1))
                .andExpect(jsonPath("$.data[0].dataAsOf").value("2026-11-01T00:30:00Z"))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(visible))
                                .param("q", "  ")
                                .param("page", Integer.toString(Integer.MAX_VALUE))
                                .param("size", "1")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));

        mvc.perform(get(PATH).param("objectTypeId", Long.toString(hidden)).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0));

        mvc.perform(get(PATH).param("q", tag + " press").with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(4))
                .andExpect(jsonPath("$.data[0].properties.cost").value(1200))
                .andExpect(jsonPath("$.data[0].restrictedPropertyCodes").isEmpty());
    }

    @Test
    void typeOptionsFollowSearchScopeAndSortByName() throws Exception {
        String tag = UUID.randomUUID().toString();
        long second = type(tag + "-b-visible", tag);
        long first = type(tag + "-a-visible", tag);
        long hidden = type(tag + "-hidden", tag + "-private");
        allowViewer(tag);

        MvcResult viewer =
                mvc.perform(get(TYPES_PATH).with(actor("VIEWER")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.meta").doesNotExist())
                        .andReturn();
        assertThat(typeIdsWithTag(viewer, tag)).containsExactly(first, second);
        String body = viewer.getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.id == " + first + ")].code"))
                .containsExactly(tag + "-a-visible");
        assertThat(JsonPath.<List<String>>read(body, "$.data[?(@.id == " + first + ")].name"))
                .containsExactly(tag + "-a-visible");

        MvcResult admin =
                mvc.perform(get(TYPES_PATH).with(actor("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(typeIdsWithTag(admin, tag)).containsExactly(first, second, hidden);

        mvc.perform(get(TYPES_PATH).with(actor("NO_SCOPE_ROLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void typeOptionsRequireUsableSession() throws Exception {
        mvc.perform(get(TYPES_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(TYPES_PATH)
                                .with(
                                        authentication(
                                                new LoginAccount(testerId, "tester", "ADMIN", true)
                                                        .toAuthentication())))
                .andExpect(status().isForbidden());
    }

    private static List<Long> typeIdsWithTag(MvcResult result, String tag) throws Exception {
        List<Map<String, Object>> data =
                JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        return data.stream()
                .filter(item -> String.valueOf(item.get("code")).startsWith(tag))
                .map(item -> ((Number) item.get("id")).longValue())
                .toList();
    }

    @Test
    void usesSeededViewerScopeForSearchResultsAndCount() throws Exception {
        String tag = UUID.randomUUID().toString();
        long production = type(tag + "-equipment", "PRODUCTION");
        long finance = type(tag + "-entry", "FINANCE");
        property(production, "cost", "INTEGER", "RESTRICTED", null);
        long visible = object(production, tag + " equipment", "{\"cost\":1200}");
        object(finance, tag + " entry", "{}");

        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) visible))
                .andExpect(jsonPath("$.data[0].properties.cost").value(nullValue()))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        mvc.perform(get(PATH).param("q", tag + " entry").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void validatesSessionPageSizeAndDeletedStatus() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(PATH)
                                .with(
                                        authentication(
                                                new LoginAccount(testerId, "tester", "ADMIN", true)
                                                        .toAuthentication())))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).param("size", "101").with(actor("VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("size"));
        mvc.perform(get(PATH).param("status", "UNKNOWN").with(actor("VIEWER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("status", "DELETED_AT_SOURCE").with(actor("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("원천에서 삭제된 객체는 관리자만 조회할 수 있습니다."))
                .andExpect(jsonPath("$.error.details.field").value("status"));
    }

    @Test
    void treatsSearchWildcardsAsLiteralCharacters() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, null);
        object(type, tag + " Press 100%", "{}");
        object(type, tag + " Press 1000", "{}");

        mvc.perform(get(PATH).param("q", tag + " press 100%").with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].displayName").value(tag + " Press 100%"));
    }

    @Test
    void searchesVisibleSourceKeysWithoutIdentifierMetadataAndKeepsTypeScope() throws Exception {
        String tag = UUID.randomUUID().toString();
        long equipment = type(tag, tag);
        allowViewer(tag);
        long code = property(equipment, "serial", "STRING", "NORMAL", null);
        long name = property(equipment, "name", "STRING", "NORMAL", null);
        long mapping = restrictedKeyMapping(equipment, code, tag);
        long press = sourceObject(equipment, mapping, "P103", "ACTIVE");
        jdbc.sql("update object_type set display_property_id = :name where id = :type")
                .param("name", name)
                .param("type", equipment)
                .update();
        jdbc.sql(
                        "update object_instance set display_name = '1라인 3호 프레스', properties = '{\"name\":\"1라인 3호 프레스\"}'::jsonb where id = :id")
                .param("id", press)
                .update();
        long otherType = type(tag + "-other", tag + "-other");
        allowViewer(tag + "-other");
        object(otherType, "P103", "{}");

        for (String query : List.of("P103", "p103", " p10 ", "1라인 3호 프레스")) {
            mvc.perform(
                            get(PATH)
                                    .param("q", query)
                                    .param("objectTypeId", Long.toString(equipment))
                                    .param("size", "8")
                                    .with(actor("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value((int) press))
                    .andExpect(jsonPath("$.data[0].sourceKey.serial").value("P103"))
                    .andExpect(jsonPath("$.meta.totalElements").value(1));
        }
    }

    @Test
    void ranksExactVisibleSourceKeysBeforePartialNamesAndEscapesWildcards() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long code = property(type, "serial", "STRING", "NORMAL", null);
        long name = property(type, "name", "STRING", "NORMAL", null);
        jdbc.sql("update object_type set display_property_id = :name where id = :type")
                .param("name", name)
                .param("type", type)
                .update();
        long mapping = restrictedKeyMapping(type, code, tag);
        long partial = object(type, "P103 부속 설비", "{}");
        long exact = sourceObject(type, mapping, "P103", "ACTIVE");
        long literal = sourceObject(type, mapping, "PX_%", "ACTIVE");
        sourceObject(type, mapping, "PX123", "ACTIVE");
        jdbc.sql(
                        "update object_instance set display_name = '이름만 표시', properties = '{\"name\":\"이름만 표시\"}'::jsonb where id in (:ids)")
                .param("ids", List.of(exact, literal))
                .update();
        for (int page = 0; page < 2; page++) {
            mvc.perform(
                            get(PATH)
                                    .param("q", "p103")
                                    .param("objectTypeId", Long.toString(type))
                                    .param("page", Integer.toString(page))
                                    .param("size", "1")
                                    .with(actor("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value((int) (page == 0 ? exact : partial)))
                    .andExpect(jsonPath("$.meta.totalElements").value(2))
                    .andExpect(jsonPath("$.meta.hasNext").value(page == 0));
        }
        mvc.perform(
                        get(PATH)
                                .param("q", "px_%")
                                .param("objectTypeId", Long.toString(type))
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) literal))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void sourceKeyVisibilityStaysSeparateForEachMappingAndMixedSensitivityColumn()
            throws Exception {
        String tag = UUID.randomUUID().toString();
        long firstType = type(tag, tag);
        long otherType = type(tag + "-other", tag);
        allowViewer(tag);
        long normal = property(firstType, "public_serial", "STRING", "NORMAL", null);
        long restricted = property(firstType, "private_serial", "STRING", "RESTRICTED", null);
        long otherNormal = property(otherType, "public_serial", "STRING", "NORMAL", null);
        long publicMapping = restrictedKeyMapping(firstType, normal, tag + "-public");
        long privateMapping = restrictedKeyMapping(firstType, restricted, tag + "-private");
        long mixedMapping = restrictedKeyMapping(firstType, normal, tag + "-mixed");
        long otherMapping = restrictedKeyMapping(otherType, otherNormal, tag + "-other");
        jdbc.sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        select source_mapping_id, source_resource_id, :restricted, source_column_id
                        from property_source_mapping where source_mapping_id = :mapping
                        """)
                .param("restricted", restricted)
                .param("mapping", mixedMapping)
                .update();
        long first = sourceObject(firstType, publicMapping, tag + "-a", "ACTIVE");
        long other = sourceObject(otherType, otherMapping, tag + "-b", "ACTIVE");
        long hidden = sourceObject(firstType, privateMapping, tag + "-private", "ACTIVE");
        long mixed = sourceObject(firstType, mixedMapping, tag + "-mixed", "ACTIVE");

        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.data[*].id").value(contains((int) first, (int) other)));
        for (long id : List.of(hidden, mixed)) {
            mvc.perform(get(PATH + "/{id}", id).with(actor("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sourceKey.serial").value(nullValue()));
            mvc.perform(get(PATH + "/{id}", id).with(actor("PLANNER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sourceKey.serial").isString());
        }
        jdbc.sql("update property_definition set sensitivity = 'RESTRICTED' where id = :id")
                .param("id", normal)
                .update();
        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) other));
    }

    @Test
    void searchingSourceKeysRespectsMaskedFieldsEvenWhenTheNameIsPublic() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long serial = property(type, "serial", "STRING", "RESTRICTED", null);
        long label = property(type, "label", "STRING", "NORMAL", null);
        long mapping = restrictedKeyMapping(type, serial, tag);
        long id = sourceObject(type, mapping, tag + "-secret", "ACTIVE");
        jdbc.sql("update object_type set display_property_id = :label where id = :type")
                .param("label", label)
                .param("type", type)
                .update();
        jdbc.sql(
                        "update object_instance set display_name = '공개 설비 이름', properties = jsonb_build_object('label', '공개 설비 이름'), source_key = source_key || jsonb_build_object('unclassified', cast(:value as text)) where id = :id")
                .param("value", tag + "-unknown")
                .param("id", id)
                .update();
        for (String suffix : List.of("-secret", "-unknown")) {
            for (String role : List.of("VIEWER", "ADMIN")) {
                mvc.perform(
                                get(PATH)
                                        .param("q", tag + suffix)
                                        .param("objectTypeId", Long.toString(type))
                                        .with(actor(role)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.meta.totalElements").value(0));
            }
            mvc.perform(
                            get(PATH)
                                    .param("q", tag + suffix)
                                    .param("objectTypeId", Long.toString(type))
                                    .with(actor("PLANNER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value((int) id))
                    .andExpect(jsonPath("$.meta.totalElements").value(1));
        }
    }

    @Test
    void ranksExactNamesAndIdentifiersBeforePartialMatchesAcrossPages() throws Exception {
        String tag = UUID.randomUUID().toString();
        String query = tag + "_%";
        String firstPartial = query + " first";
        String partialPrefix = query + " partial ";
        long type = type(tag, null);
        identifierProperty(type, "equipment_code", "NORMAL");
        object(type, firstPartial, "{}");
        List<Integer> partialIds =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                select :type, :prefix || n, '{}'::jsonb
                                from generate_series(1, 100) n
                                returning id
                                """)
                        .param("type", type)
                        .param("prefix", partialPrefix)
                        .query(Integer.class)
                        .list();
        Integer[] lastPartialIds =
                partialIds.stream().sorted().skip(partialIds.size() - 3L).toArray(Integer[]::new);
        object(type, query, "{}");
        object(type, "Press", "{\"equipment_code\":\"%s\"}".formatted(query));
        object(type, tag + "XY", "{}");

        mvc.perform(
                        get(PATH)
                                .param("q", "  " + query.toUpperCase(Locale.ROOT) + "  ")
                                .param("size", "100")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[0:3].displayName", contains(query, "Press", firstPartial)))
                .andExpect(jsonPath("$.data.length()").value(100))
                .andExpect(jsonPath("$.meta.totalElements").value(103))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mvc.perform(
                        get(PATH)
                                .param("q", query)
                                .param("size", "100")
                                .param("page", "1")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", contains(lastPartialIds)))
                .andExpect(jsonPath("$.meta.totalElements").value(103))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void restrictedIdentifierDoesNotRankAVisiblePartialMatchAsExact() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        identifierProperty(type, "serial", "RESTRICTED");
        String firstPartial = tag + " first";
        String restrictedExact = tag + " second";
        object(type, firstPartial, "{}");
        object(type, restrictedExact, "{\"serial\":\"%s\"}".formatted(tag));

        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].displayName", contains(firstPartial, restrictedExact)));
        mvc.perform(get(PATH).param("q", tag).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].displayName", contains(restrictedExact, firstPartial)));
    }

    @Test
    void masksRestrictedSourceKeyAndNameBeforeSearchingForTheValue() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long property = property(type, "serial", "STRING", "RESTRICTED", null);
        long mapping = restrictedKeyMapping(type, property, tag);
        long active = sourceObject(type, mapping, tag + "-active", "ACTIVE");
        sourceObject(type, mapping, tag + "-deleted", "DELETED_AT_SOURCE");

        mvc.perform(get(PATH).param("objectTypeId", Long.toString(type)).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].displayName").value("#" + active))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(nullValue()))
                .andExpect(jsonPath("$.data[0].properties.serial").value(nullValue()));
        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));

        mvc.perform(get(PATH).param("q", tag).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(tag + "-active"));
        // 원천에서 지워진 객체는 ADMIN만 찾는다(258 전까지 역할 이름 검사). ADMIN은 민감 값을 볼 수 없어(V21, 401) 그 객체의 민감 키는
        // 가려지고,
        // 민감 키 값으로는 찾지 못한다.
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(type))
                                .param("status", "DELETED_AT_SOURCE")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("DELETED_AT_SOURCE"))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(nullValue()));
        mvc.perform(
                        get(PATH)
                                .param("q", tag)
                                .param("status", "DELETED_AT_SOURCE")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    void neverSearchesTheStaleDisplayNameOrUnclassifiedKeyAfterAMappingChange() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long restricted = property(type, "serial", "STRING", "RESTRICTED", null);
        long label = property(type, "public_label", "STRING", "NORMAL", null);
        long mapping = restrictedKeyMapping(type, restricted, tag);
        long id = sourceObject(type, mapping, tag + "-sensitive", "ACTIVE");
        jdbc.sql("delete from property_source_mapping where source_mapping_id = :mapping")
                .param("mapping", mapping)
                .update();
        jdbc.sql("update object_type set display_property_id = :label where id = :type")
                .param("label", label)
                .param("type", type)
                .update();
        jdbc.sql(
                        """
                        update object_instance
                        set properties = properties || jsonb_build_object('public_label', cast(:name as text))
                        where id = :id
                        """)
                .param("name", tag + "-readable")
                .param("id", id)
                .update();

        mvc.perform(get(PATH).param("q", tag + "-sensitive").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mvc.perform(get(PATH).param("q", tag + "-readable").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayName").value(tag + "-readable"))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(nullValue()))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void searchesOnlyIdentifiersWhoseValuesTheActorCanRead() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        identifierProperty(type, "equipment_code", "NORMAL");
        identifierProperty(type, "serial", "RESTRICTED");
        object(
                type,
                "Press",
                """
                {"equipment_code":"%s-public","serial":"%s-private"}
                """
                        .formatted(tag, tag));

        mvc.perform(get(PATH).param("q", tag + "-public").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        mvc.perform(get(PATH).param("q", tag + "-private").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mvc.perform(get(PATH).param("q", tag + "-private").with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    /** 372: 대표 속성이 없어 표시 이름이 원천 키(P101)인 설비를 name 값으로 찾는다. 076은 그대로 찾지 못한다. */
    @Test
    void findsActiveObjectsWhoseStringPropertyEqualsTheValueWithinScope() throws Exception {
        String tag = UUID.randomUUID().toString();
        long equipment = type(tag, tag);
        long hidden = type(tag + "-hidden", tag + "-private");
        allowViewer(tag);
        identifierProperty(equipment, "code", "NORMAL");
        property(equipment, "name", "STRING", "NORMAL", null);
        property(hidden, "name", "STRING", "NORMAL", null);
        String name = tag + " 1라인 1호 프레스";
        long press =
                object(equipment, "P101", "{\"code\":\"P101\",\"name\":\"%s\"}".formatted(name));
        object(equipment, "P102", "{\"code\":\"P102\",\"name\":\"%s 옆\"}".formatted(name));
        object(equipment, "P103", "{\"code\":\"P103\",\"alias\":\"%s\"}".formatted(name));
        long hiddenPress = object(hidden, "H101", "{\"name\":\"%s\"}".formatted(name));

        mvc.perform(get(PATH).param("q", name).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        assertThat(searchQuery.getObject().findByPropertyValue(account("VIEWER"), " " + name, 100))
                .as("값이 정확히 같은 정의된 문자 속성만, 볼 수 있는 유형에서만 찾는다")
                .extracting(ObjectSearchResponse::id, ObjectSearchResponse::displayName)
                .containsExactly(tuple(press, "P101"));
        assertThat(searchQuery.getObject().findByPropertyValue(account("ADMIN"), name, 100))
                .extracting(ObjectSearchResponse::id)
                .containsExactly(press, hiddenPress);
        assertThat(searchQuery.getObject().findByPropertyValue(account("ADMIN"), name, 1))
                .extracting(ObjectSearchResponse::id)
                .containsExactly(press);
        assertThat(searchQuery.getObject().findByPropertyValue(account("ADMIN"), "  ", 100))
                .isEmpty();
    }

    @Test
    void findsByPropertyValueOnlyWhereTheActorCanReadTheValue() {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long serial = property(type, "serial", "STRING", "RESTRICTED", null);
        property(type, "public_label", "STRING", "NORMAL", null);
        long mapping = restrictedKeyMapping(type, serial, tag);
        long active = sourceObject(type, mapping, tag + "-active", "ACTIVE");
        sourceObject(type, mapping, tag + "-deleted", "DELETED_AT_SOURCE");
        long keyOnly = sourceObject(type, mapping, tag + "-key-only", "ACTIVE");
        jdbc.sql(
                        """
                        update object_instance
                        set properties = jsonb_build_object('public_label', cast(:label as text))
                        where id = :id
                        """)
                .param("label", tag + "-label")
                .param("id", keyOnly)
                .update();
        LoginAccount viewer = account("VIEWER");
        LoginAccount admin = account("PLANNER");
        var search = searchQuery.getObject();

        assertThat(search.findByPropertyValue(viewer, tag + "-active", 100))
                .as("민감 속성 값으로는 찾지 않는다")
                .isEmpty();
        assertThat(search.findByPropertyValue(viewer, tag + "-key-only", 100))
                .as("가린 원천 키·표시 이름 값으로는 찾지 않는다")
                .isEmpty();
        assertThat(search.findByPropertyValue(viewer, tag + "-label", 100))
                .singleElement()
                .satisfies(
                        found -> {
                            assertThat(found.id()).isEqualTo(keyOnly);
                            assertThat(found.displayName()).isEqualTo("#" + keyOnly);
                            assertThat(found.sourceKey()).containsEntry("serial", null);
                        });
        assertThat(search.findByPropertyValue(admin, tag + "-active", 100))
                .extracting(ObjectSearchResponse::id)
                .containsExactly(active);
        assertThat(search.findByPropertyValue(admin, tag + "-deleted", 100))
                .as("원천에서 삭제된 객체는 찾지 않는다")
                .isEmpty();
        assertThat(search.findByPropertyValue(admin, tag + "-key-only", 100)).isEmpty();
    }

    private LoginAccount account(String role) {
        return new LoginAccount(testerId, "tester", role, false);
    }

    @Test
    void detailMasksValuesAndKeepsPropertyCodesAndNames() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long serial = property(type, "serial", "STRING", "RESTRICTED", null);
        jdbc.sql("update property_definition set name = '일련번호' where id = :id")
                .param("id", serial)
                .update();
        long mapping = restrictedKeyMapping(type, serial, tag);
        long id = sourceObject(type, mapping, "secret-serial", "ACTIVE");

        mvc.perform(get(PATH + "/{id}", id).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("#" + id))
                .andExpect(jsonPath("$.data.properties.serial").value(nullValue()))
                .andExpect(jsonPath("$.data.propertyNames.serial").value("일련번호"))
                .andExpect(jsonPath("$.data.restrictedPropertyCodes[0]").value("serial"))
                .andExpect(jsonPath("$.data.sourceSystemName").value(tag))
                .andExpect(jsonPath("$.data.sourceResourceKey").value("public.equipment"))
                .andExpect(jsonPath("$.data.sourceKey.serial").value(nullValue()));

        mvc.perform(get(PATH + "/{id}", id).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.properties.serial").value("secret-serial"))
                .andExpect(jsonPath("$.data.sourceKey.serial").value("secret-serial"))
                .andExpect(jsonPath("$.data.restrictedPropertyCodes").isEmpty());
    }

    @Test
    void linksFilterVisibilityStatusDirectionAndTypeBeforePagination() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        long hiddenType = type(tag + "-hidden", null);
        allowViewer(tag);
        long anchor = object(type, "Anchor", "{}");
        long incoming = object(type, "Incoming", "{}");
        long hidden = object(hiddenType, "Hidden", "{}");
        long serial = property(type, "serial", "STRING", "RESTRICTED", null);
        long mapping = restrictedKeyMapping(type, serial, tag);
        long outgoing = sourceObject(type, mapping, "private-label", "ACTIVE");
        long deleted = sourceObject(type, mapping, "deleted", "DELETED_AT_SOURCE");
        long inactive = object(type, "Inactive", "{}");
        long relation = linkType(tag, type, type);
        long hiddenRelation = linkType(tag + "-hidden", type, hiddenType);
        link(hiddenRelation, anchor, hidden, "ACTIVE");
        link(relation, anchor, deleted, "ACTIVE");
        link(relation, anchor, inactive, "INACTIVE");
        long out = link(relation, anchor, outgoing, "ACTIVE");
        long in = link(relation, incoming, anchor, "ACTIVE");
        long anotherRelation = linkType(tag + "-other", type, type);
        link(anotherRelation, anchor, incoming, "ACTIVE");

        mvc.perform(get(PATH + "/{id}/links", anchor).param("size", "1").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) out))
                .andExpect(jsonPath("$.data[0].linkTypeName").value("Related"))
                .andExpect(jsonPath("$.data[0].relatedObject.id").value((int) outgoing))
                .andExpect(jsonPath("$.data[0].relatedObject.displayName").value("#" + outgoing))
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(true))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mvc.perform(
                        get(PATH + "/{id}/links", anchor)
                                .param("direction", "IN")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) in))
                .andExpect(jsonPath("$.data[0].sourceObjectId").value((int) incoming))
                .andExpect(jsonPath("$.data[0].targetObjectId").value((int) anchor))
                .andExpect(jsonPath("$.data[0].relatedObject.id").value((int) incoming))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        mvc.perform(
                        get(PATH + "/{id}/links", anchor)
                                .param("page", "1")
                                .param("size", "1")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) in))
                .andExpect(jsonPath("$.meta.totalElements").value(3));

        mvc.perform(
                        get(PATH + "/{id}/links", anchor)
                                .param("direction", "OUT")
                                .param("linkTypeId", Long.toString(relation))
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) out))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void linkGroupsCountEveryVisibleTypeAndDirectionLikeLinks() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        long other = type(tag + "-other", tag);
        long hiddenType = type(tag + "-hidden", null);
        allowViewer(tag);
        long anchor = object(type, "Anchor", "{}");
        long incoming = object(type, "Incoming", "{}");
        long outgoing = object(type, "Outgoing", "{}");
        long hidden = object(hiddenType, "Hidden", "{}");
        long toOther = linkType(tag + "-to-other", type, other);
        long same = linkType(tag + "-same", type, type);
        long toHidden = linkType(tag + "-to-hidden", type, hiddenType);
        for (int i = 0; i < 3; i++) {
            link(toOther, anchor, object(other, "Other " + i, "{}"), "ACTIVE");
        }
        link(toOther, anchor, object(other, "Inactive", "{}"), "INACTIVE");
        link(same, anchor, outgoing, "ACTIVE");
        link(same, incoming, anchor, "ACTIVE");
        link(toHidden, anchor, hidden, "ACTIVE");

        mvc.perform(get(PATH + "/{id}/link-groups", anchor).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].linkTypeId").value((int) toOther))
                .andExpect(jsonPath("$.data[0].linkTypeName").value("Related"))
                .andExpect(jsonPath("$.data[0].direction").value("OUT"))
                .andExpect(jsonPath("$.data[0].relatedObjectTypeId").value((int) other))
                .andExpect(jsonPath("$.data[0].relatedObjectTypeName").value(tag + "-other"))
                .andExpect(jsonPath("$.data[0].linkCount").value(3))
                .andExpect(jsonPath("$.data[1].linkTypeId").value((int) same))
                .andExpect(jsonPath("$.data[1].direction").value("OUT"))
                .andExpect(jsonPath("$.data[1].linkCount").value(1))
                .andExpect(jsonPath("$.data[2].linkTypeId").value((int) same))
                .andExpect(jsonPath("$.data[2].direction").value("IN"))
                .andExpect(jsonPath("$.data[2].relatedObjectTypeName").value(tag))
                .andExpect(jsonPath("$.data[2].linkCount").value(1));

        mvc.perform(
                        get(PATH + "/{id}/links", anchor)
                                .param("linkTypeId", Long.toString(toOther))
                                .param("direction", "OUT")
                                .param("size", "2")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mvc.perform(get(PATH + "/{id}/link-groups", hidden).with(actor("VIEWER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/{id}/link-groups", outgoing).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].direction").value("IN"));
    }

    @Test
    void documentEvidenceOnlyShowsTextToActorsWithRestrictedAccess() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long source = object(type, "Document", "{}");
        long target = object(type, "Equipment", "{}");
        long relation = linkType(tag, type, type);
        long confirmer =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                                select :login, 'Test admin', 'test-hash', id, false from role where code = 'ADMIN'
                                returning id
                                """)
                        .param("login", "link-" + tag)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin, confirmed_by, evidence)
                        values (:type, :source, :target, 'DOCUMENT', :confirmer,
                                '{"expression_kind":"ESTIMATED","quote":"private document text",
                                  "event":"noise","time_expression":"night shift","document_id":"private-id"}'::jsonb)
                        """)
                .param("type", relation)
                .param("source", source)
                .param("target", target)
                .param("confirmer", confirmer)
                .update();

        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].origin").value("DOCUMENT"))
                .andExpect(jsonPath("$.data[0].expressionKind").value("ESTIMATED"))
                .andExpect(jsonPath("$.data[0].confirmedBy").value((int) confirmer))
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(true))
                .andExpect(jsonPath("$.data[0].evidence.quote").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].evidence.event").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].evidence.timeExpression").doesNotHaveJsonPath());
        mvc.perform(get(PATH + "/{id}/links", target).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(false))
                .andExpect(jsonPath("$.data[0].evidence.quote").value("private document text"))
                .andExpect(jsonPath("$.data[0].evidence.event").value("noise"))
                .andExpect(jsonPath("$.data[0].evidence.timeExpression").value("night shift"))
                .andExpect(jsonPath("$.data[0].evidence.document_id").doesNotHaveJsonPath());

        jdbc.sql(
                        """
                        update object_link set evidence = '{"expression_kind":"ESTIMATED"}'::jsonb
                        where link_type_id = :type and source_object_id = :source
                        """)
                .param("type", relation)
                .param("source", source)
                .update();
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(false));

        jdbc.sql(
                        """
                        update object_link set origin = 'MANUAL', evidence = '{}'::jsonb
                        where link_type_id = :type and source_object_id = :source
                        """)
                .param("type", relation)
                .param("source", source)
                .update();
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].origin").value("MANUAL"))
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(false));
    }

    @Test
    @Transactional
    void ruleEvidenceUsesTheSourceKeyAndStoredValuesInBothDirections() throws Exception {
        String tag = UUID.randomUUID().toString();
        long sourceType = type(tag + "-source", tag);
        long targetType = type(tag + "-target", tag);
        allowViewer(tag);
        long sourceProperty = property(sourceType, "code", "STRING", "NORMAL", null);
        long targetProperty = property(targetType, "code", "STRING", "NORMAL", null);
        long serial = property(sourceType, "serial", "STRING", "RESTRICTED", null);
        long mapping = restrictedKeyMapping(sourceType, serial, tag);
        long source = sourceObject(sourceType, mapping, "private-serial", "ACTIVE");
        long target = object(targetType, "Target", "{}");
        long relation = linkType(tag, sourceType, targetType);
        long link = link(relation, source, target, "ACTIVE");
        condition(link, sourceProperty, targetProperty, 1);

        for (long objectId : new long[] {source, target}) {
            mvc.perform(get(PATH + "/{id}/links", objectId).with(actor("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].evidence.sourceKey.serial").value(nullValue()))
                    .andExpect(
                            jsonPath("$.data[0].evidence.transformedValue").value("private-label"))
                    .andExpect(jsonPath("$.data[0].evidence.rawValue").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.data[0].evidence.comparisons").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.meta.totalElements").value(1));
        }

        LoginAccount admin = new LoginAccount(testerId, "tester", "PLANNER", false);
        var evidence = evidenceQuery.getObject().linkEvidence(admin, target, link);
        assertThat(evidence.evidenceRestricted()).isFalse();
        assertThat(evidence.sourceKey()).containsEntry("serial", "private-serial");
        assertThat(evidence.transformedValue()).isEqualTo("private-label");
        assertThat(evidence.rawValue()).isNull();

        long unrelated = object(targetType, "Unrelated", "{}");
        assertThatThrownBy(() -> evidenceQuery.getObject().linkEvidence(admin, unrelated, link))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @Transactional
    void multiConditionRuleEvidenceIsHiddenIfOneTargetPropertyIsRestricted() throws Exception {
        String tag = UUID.randomUUID().toString();
        long sourceType = type(tag + "-source", tag);
        long targetType = type(tag + "-target", tag);
        allowViewer(tag);
        long sourceProperty = property(sourceType, "code", "STRING", "NORMAL", null);
        long targetProperty = property(targetType, "code", "STRING", "NORMAL", null);
        long sourceSerial = property(sourceType, "serial", "STRING", "NORMAL", null);
        long targetSerial = property(targetType, "serial", "STRING", "RESTRICTED", null);
        long source = object(sourceType, "Source", "{}");
        long targetMapping = restrictedKeyMapping(targetType, targetProperty, tag);
        long target = sourceObject(targetType, targetMapping, "target-serial", "ACTIVE");
        long link = link(linkType(tag, sourceType, targetType), source, target, "ACTIVE");
        condition(link, sourceProperty, targetProperty, 1);
        condition(link, sourceSerial, targetSerial, 2);
        jdbc.sql(
                        """
                        update object_link
                        set evidence = '{
                            "rawValues":{"1":"old raw","2":"private serial"},
                            "targetRawValues":{"2":"target serial"},
                            "matchedValues":{"1":"CODE","2":"PRIVATE SERIAL"}
                        }'::jsonb
                        where id = :id
                        """)
                .param("id", link)
                .update();

        mvc.perform(get(PATH + "/{id}/links", source).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.rawValue['1']").value("old raw"))
                .andExpect(jsonPath("$.data[0].evidence.rawValue['2']").value("private serial"))
                .andExpect(jsonPath("$.data[0].evidence.transformedValue['1']").value("CODE"))
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[0].sourceProperty").value("code"))
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[0].sourceColumn")
                                .doesNotHaveJsonPath())
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[0].sourceValue").value("old raw"))
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[0].targetProperty").value("code"))
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[0].targetColumn").value("serial"))
                .andExpect(jsonPath("$.data[0].evidence.comparisons[0].targetValue").value("CODE"))
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[1].sourceProperty")
                                .value("serial"))
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[1].targetValue")
                                .value("target serial"))
                .andExpect(
                        jsonPath("$.data[0].evidence.transformedValue['2']")
                                .value("PRIVATE SERIAL"));
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(true))
                .andExpect(jsonPath("$.data[0].evidence.rawValue").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].evidence.transformedValue").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].evidence.comparisons").doesNotHaveJsonPath());
        LoginAccount viewer = new LoginAccount(testerId, "tester", "VIEWER", false);
        var hiddenEvidence = evidenceQuery.getObject().linkEvidence(viewer, source, link);
        assertThat(hiddenEvidence.evidenceRestricted()).isTrue();
        assertThat(hiddenEvidence.transformedValue()).isNull();

        jdbc.sql(
                        """
                        update object_link set evidence = '{"rawValues":{"1":"raw","2":42}}'::jsonb
                        where id = :id
                        """)
                .param("id", link)
                .update();
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.rawValue").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].evidence.comparisons").doesNotHaveJsonPath());
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(false));

        jdbc.sql(
                        """
                        update object_link
                        set evidence = evidence || '{"matchedValues":{"1":"MATCH","2":42}}'::jsonb
                        where id = :id
                        """)
                .param("id", link)
                .update();
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[0].evidence.comparisons[0].matchedValue").value("MATCH"));
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(true))
                .andExpect(jsonPath("$.data[0].evidence.comparisons").doesNotHaveJsonPath());

        jdbc.sql("update object_link set evidence = '{}'::jsonb where id = :id")
                .param("id", link)
                .update();
        mvc.perform(get(PATH + "/{id}/links", source).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidence.evidenceRestricted").value(false));

        jdbc.sql(
                        "update object_link set status = 'INACTIVE', inactivated_at = now() where id = :id")
                .param("id", link)
                .update();
        assertThatThrownBy(() -> evidenceQuery.getObject().linkEvidence(viewer, source, link))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void detailAndLinksHideObjectsOutsideScopeAndValidateInput() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long visible = object(type, "Visible", "{}");
        long hidden = object(type(tag + "-hidden", null), "Hidden", "{}");
        long serial = property(type, "serial", "STRING", "RESTRICTED", null);
        long mapping = restrictedKeyMapping(type, serial, tag);
        long deleted = sourceObject(type, mapping, "deleted", "DELETED_AT_SOURCE");

        for (String path :
                new String[] {PATH + "/{id}", PATH + "/{id}/links", PATH + "/{id}/link-groups"}) {
            mvc.perform(get(path, visible)).andExpect(status().isUnauthorized());
            mvc.perform(get(path, 0).with(actor("VIEWER"))).andExpect(status().isBadRequest());
            for (long id : new long[] {hidden, deleted, Long.MAX_VALUE}) {
                mvc.perform(get(path, id).with(actor("VIEWER")))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
            }
        }
        mvc.perform(get(PATH + "/{id}", deleted).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED_AT_SOURCE"));
        mvc.perform(get(PATH + "/{id}/links", deleted).with(actor("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/{id}/links", visible).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mvc.perform(
                        get(PATH + "/{id}/links", visible)
                                .param("direction", "WRONG")
                                .with(actor("VIEWER")))
                .andExpect(status().isBadRequest());
    }

    private long linkType(String code, long source, long target) {
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, 'Related', :source, :target, 'MANY_TO_MANY', 'Test') returning id
                        """)
                .param("code", code)
                .param("source", source)
                .param("target", target)
                .query(Long.class)
                .single();
    }

    /** 관계 유형마다 반영 중 규칙은 하나라(V17) 그 유형의 규칙이 있으면 그 규칙을 근거로 쓴다. */
    private long link(long type, long source, long target, String status) {
        return jdbc.sql(
                        """
                        with existing as (
                            select id from link_mapping where link_type_id = :type and status = 'ACTIVE'
                        ), created as (
                            insert into link_mapping (link_type_id, code, revision)
                            select :type, :code, 1 where not exists (select 1 from existing)
                            returning id
                        ), rule as (
                            select id from created union all select id from existing
                        )
                        insert into object_link
                            (link_type_id, source_object_id, target_object_id, origin,
                             link_mapping_id, status, inactivated_at, evidence)
                        select :type, :source, :target, 'RULE', id, :status,
                               case when :status = 'INACTIVE' then now() end,
                               '{"matchedValues":{"1":"private-label"}}'::jsonb
                        from rule returning id
                        """)
                .param("type", type)
                .param("code", UUID.randomUUID().toString())
                .param("source", source)
                .param("target", target)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private void condition(long link, long sourceProperty, long targetProperty, int sequence) {
        jdbc.sql(
                        """
                insert into link_mapping_condition
                    (link_mapping_id, source_property_id, target_property_id, sequence_no)
                select link_mapping_id, :sourceProperty, :targetProperty, :sequence
                from object_link where id = :link
                """)
                .param("link", link)
                .param("sourceProperty", sourceProperty)
                .param("targetProperty", targetProperty)
                .param("sequence", sequence)
                .update();
    }

    /**
     * 401: 마이그레이션(V21)이 넣은 운영 그룹 행 그대로면 ADMIN은 민감 값을 보지 못하고 PLANNER만 본다. 시험 도우미({@link #type})가 행을
     * 넣지 않는 운영 그룹(PRODUCTION)으로 확인한다.
     */
    @Test
    void seededAdminCannotSeeRestrictedValues() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type =
                jdbc.sql(
                                "insert into object_type (code, name, access_group) values (:code, :code, 'PRODUCTION') returning id")
                        .param("code", "SEEDED_" + tag.substring(0, 8))
                        .query(Long.class)
                        .single();
        property(type, "technician", "STRING", "RESTRICTED", null);
        long id = object(type, tag + " mo", "{\"technician\":\"T-" + tag.substring(0, 6) + "\"}");

        mvc.perform(get(PATH + "/" + id).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.properties.technician").value(nullValue()))
                .andExpect(jsonPath("$.data.restrictedPropertyCodes", contains("technician")));
        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(type))
                                .param("propertyValues[technician]", "T-" + tag.substring(0, 6))
                                .with(actor("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/" + id).with(actor("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.properties.technician").value("T-" + tag.substring(0, 6)));
    }

    private RequestPostProcessor actor(String role) {
        return authentication(new LoginAccount(testerId, "tester", role, false).toAuthentication());
    }

    /**
     * 시험용 유형. ADMIN에게는 그 그룹을 민감 값 없이, PLANNER에게는 민감 값까지 열어 준다(V21과 같다, 401). 384부터 모든 역할이 허용 행으로만
     * 보고 그룹 없는 유형은 아무도 못 보므로 두 역할의 행을 넣는다. 민감 값을 보는 쪽은 PLANNER로 시험한다. accessGroup이 null이면 이 두 역할만
     * 허용한 그룹을 따로 만든다.
     */
    private long type(String name, String accessGroup) {
        String group = accessGroup == null ? "admin-only-" + name : accessGroup;
        long id =
                jdbc.sql(
                                """
                                insert into object_type (code, name, access_group)
                                values (:code, :code, :group) returning id
                                """)
                        .param("code", name)
                        .param("group", group)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, code = 'PLANNER' from role where code in ('ADMIN', 'PLANNER')
                        on conflict (role_id, access_group) do nothing
                        """)
                .param("group", group)
                .update();
        return id;
    }

    private void allowViewer(String accessGroup) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, false from role where code = 'VIEWER'
                        """)
                .param("group", accessGroup)
                .update();
    }

    private long property(
            long type, String code, String dataType, String sensitivity, String temporalRole) {
        return jdbc.sql(
                        """
                        insert into property_definition
                            (object_type_id, code, name, data_type, sensitivity, temporal_role)
                        values (:type, :code, :code, :dataType, :sensitivity, :temporalRole) returning id
                        """)
                .param("type", type)
                .param("code", code)
                .param("dataType", dataType)
                .param("sensitivity", sensitivity)
                .param("temporalRole", temporalRole)
                .query(Long.class)
                .single();
    }

    private void identifierProperty(long type, String code, String sensitivity) {
        long id = property(type, code, "STRING", sensitivity, null);
        jdbc.sql("update property_definition set is_identifier = true where id = :id")
                .param("id", id)
                .update();
    }

    private long restrictedKeyMapping(long type, long property, String code) {
        long resource =
                jdbc.sql(
                                """
                        with source as (
                            insert into data_source (code, name, source_kind)
                            values (:code, :code, 'POSTGRES') returning id
                        )
                        insert into source_resource (data_source_id, resource_key, resource_kind)
                        select id, 'public.equipment', 'TABLE' from source returning id
                        """)
                        .param("code", code)
                        .query(Long.class)
                        .single();
        long mapping =
                jdbc.sql(
                                """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:resource, :type, '["serial"]'::jsonb) returning id
                        """)
                        .param("resource", resource)
                        .param("type", type)
                        .query(Long.class)
                        .single();
        long column =
                jdbc.sql(
                                """
                        insert into source_column
                            (source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                        values (:resource, 'serial', 'text', false, 1) returning id
                        """)
                        .param("resource", resource)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column)
                        """)
                .param("mapping", mapping)
                .param("resource", resource)
                .param("property", property)
                .param("column", column)
                .update();
        return mapping;
    }

    private long sourceObject(long type, long mapping, String serial, String status) {
        return jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties, status)
                        values (:type, :mapping, jsonb_build_object('serial', cast(:serial as text)),
                                :serial, jsonb_build_object('serial', cast(:serial as text)), :status)
                        returning id
                        """)
                .param("type", type)
                .param("mapping", mapping)
                .param("serial", serial)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long object(long type, String name, String properties) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb)) returning id
                        """)
                .param("type", type)
                .param("name", name)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }
}
