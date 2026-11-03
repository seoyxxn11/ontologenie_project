package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.service.QueuedJobPoller;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

// keep-alive는 테스트가 끝나도 JVM을 붙잡으므로 테스트에서만 끈다.
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class BatchProfileTests {

    @Autowired ApplicationContext context;

    @Autowired DataSource dataSource;

    @Test
    void startsWithoutWebServer() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
    }

    @Test
    void connectsToDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void sharesAuditRecorderWithApi() {
        assertThat(context.getBean(AuditRecorder.class)).isNotNull();
    }

    @Test
    void pollsQueuedJobs() {
        assertThat(context.getBean(QueuedJobPoller.class)).isNotNull();
    }
}
