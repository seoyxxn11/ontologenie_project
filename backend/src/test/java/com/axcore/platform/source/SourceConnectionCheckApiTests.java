package com.axcore.platform.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/** 연결 검사 접수(API 013)와 결과 조회(API 100). 실제 검사는 배치가 하므로 여기서는 작업 표를 직접 바꿔 끝난 상태를 만든다. */
@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourceConnectionCheckApiTests extends SourceApiTestSupport {

    @Test
    void queuesCheckAndJoinsActiveOne() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());

        long checkId = requestCheck(admin, sourceId, false);
        long joinedId = requestCheck(admin, sourceId, true);

        assertThat(joinedId).isEqualTo(checkId);
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select count(*) from action_execution
                                        where event_kind = 'SOURCE_CHECK'
                                          and target_type = 'source_connection_check' and target_id = :id
                                        """)
                                .param("id", checkId)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        mockMvc.perform(get(BASE_PATH + "/" + sourceId).cookie(admin.session()))
                .andExpect(jsonPath("$.data.latestConnectionCheck.checkId").value(checkId))
                .andExpect(jsonPath("$.data.latestConnectionCheck.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.latestConnectionCheck.finishedAt").value(nullValue()));

        finish(checkId);
        long nextId = requestCheck(admin, sourceId, false);

        assertThat(nextId).isNotEqualTo(checkId);
    }

    @Test
    void readsResultOfOwnSourceOnly() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long otherSourceId = createPostgresSource(admin, uniqueCode());
        long checkId = requestCheck(admin, sourceId, false);

        mockMvc.perform(
                        get(BASE_PATH + "/" + sourceId + "/connection-checks/" + checkId)
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.requestedBy").value(admin.accountId()))
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.result.accounts").isEmpty());

        finish(checkId);
        mockMvc.perform(
                        get(BASE_PATH + "/" + sourceId + "/connection-checks/" + checkId)
                                .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.accounts[0].purpose").value("SYNC"))
                .andExpect(jsonPath("$.data.result.accounts[0].readableTableCount").value(17))
                .andExpect(jsonPath("$.data.result.accounts[1].purpose").value("AI"))
                .andExpect(jsonPath("$.data.result.accounts[1].status").value("FAILED"))
                .andExpect(
                        jsonPath("$.data.result.accounts[1].readableTableCount").value(nullValue()))
                .andExpect(jsonPath("$.data.result.accounts[1].errorMessage").value("권한 없음"))
                .andExpect(jsonPath("$.data.result.accounts[1].host").doesNotExist());
        mockMvc.perform(
                        get(BASE_PATH + "/" + otherSourceId + "/connection-checks/" + checkId)
                                .cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingSourceAndNonAdmin() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        LoginSession viewer = loginAs("VIEWER");
        long sourceId = createPostgresSource(admin, uniqueCode());

        send(admin, post(BASE_PATH + "/999999999/test-connection"), null)
                .andExpect(status().isNotFound());
        send(viewer, post(BASE_PATH + "/" + sourceId + "/test-connection"), null)
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get(BASE_PATH + "/" + sourceId + "/connection-checks/1")
                                .cookie(viewer.session()))
                .andExpect(status().isForbidden());
    }

    private long requestCheck(LoginSession admin, long sourceId, boolean joined) throws Exception {
        MvcResult result =
                send(admin, post(BASE_PATH + "/" + sourceId + "/test-connection"), null)
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.joined").value(joined))
                        .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                        .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.checkId"))
                .longValue();
    }

    /** 배치가 검사를 끝낸 것처럼 결과를 채운다. AI 계정에는 쓰면 안 되는 칸(host)도 넣어 응답에서 빠지는지 본다. */
    private void finish(long checkId) {
        jdbcClient
                .sql(
                        """
                        update source_connection_check
                        set status = 'SUCCEEDED', started_at = now(), finished_at = now(),
                            result = '{"accounts": [
                              {"purpose": "SYNC", "status": "SUCCEEDED", "readableTableCount": 17,
                               "readableColumnCount": 140, "errorMessage": null},
                              {"purpose": "AI", "status": "FAILED", "readableTableCount": null,
                               "readableColumnCount": null, "errorMessage": "권한 없음", "host": "10.0.0.5"}]}'
                        where id = :id
                        """)
                .param("id", checkId)
                .update();
    }
}
