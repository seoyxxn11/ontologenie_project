package com.axcore.platform.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** 대량 적재용 Spring Batch Job이 실행 기록을 플랫폼 DB(V6 테이블)에 남기는지 확인한다. */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import({TestcontainersConfiguration.class, SpringBatchJobRepositoryTests.JobConfig.class})
class SpringBatchJobRepositoryTests {

    @Autowired JobOperator jobOperator;

    @Autowired Job sampleJob;

    @Autowired JdbcClient jdbcClient;

    @Test
    void storesJobExecutionInPlatformDatabase() throws Exception {
        JobExecution execution =
                jobOperator.start(
                        sampleJob,
                        new JobParametersBuilder().addLong("dataSourceId", 1L).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select e.status from batch_job_execution e
                                        join batch_job_instance i using (job_instance_id)
                                        where i.job_name = 'sampleJob'
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly("COMPLETED");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JobConfig {

        @Bean
        Job sampleJob(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
            return new JobBuilder("sampleJob", jobRepository)
                    .start(
                            new StepBuilder("sampleStep", jobRepository)
                                    .tasklet(
                                            (contribution, context) -> RepeatStatus.FINISHED,
                                            transactionManager)
                                    .build())
                    .build();
        }
    }
}
