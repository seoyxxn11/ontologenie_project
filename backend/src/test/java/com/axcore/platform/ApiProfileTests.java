package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@AutoConfigureMetrics
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ApiProfileTests {

    @Autowired ApplicationContext context;

    @Autowired MockMvc mockMvc;

    @Test
    void startsAsWebApplication() {
        assertThat(context).isInstanceOf(WebApplicationContext.class);
    }

    @Test
    void doesNotRunQueuedJobs() {
        // api는 작업 표에 QUEUED 행을 넣기만 하고 실행은 배치 컨테이너가 한다(2026-10-30 결정).
        assertThat(context.getBeanNamesForType(QueuedJobPoller.class)).isEmpty();
        assertThat(context.getBeanNamesForType(QueuedJobRunner.class)).isEmpty();
    }

    @Test
    void healthIsUpWithDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesPrometheusJvmMetricsWithoutLogin() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void otherActuatorPathsStillRequireLogin() throws Exception {
        for (String path :
                new String[] {"/actuator/env", "/actuator/metrics", "/actuator/prometheus/extra"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void prometheusPostStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/actuator/prometheus")).andExpect(status().isForbidden());
    }

    @Test
    void servesOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.1")))
                .andExpect(jsonPath("$.info.title").value("AXCore Platform API"))
                .andExpect(jsonPath("$.paths['/error']").doesNotExist());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
