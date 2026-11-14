package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueStatusChangeRequest;
import com.axcore.platform.issue.service.IssueStatusService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** API 067 상태 변경이 실제로 커밋될 때의 동시성. 테스트 트랜잭션 없이 각 변경이 자기 트랜잭션으로 커밋되게 한다. 만든 행은 끝나고 지운다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class IssueStatusConcurrencyTests {

    private static final String EVIDENCE = "{\"source\": \"MES\"}";

    @Autowired IssueStatusService statusService;

    @Autowired JdbcClient jdbcClient;

    @Autowired DataSource dataSource;

    private IssueTestFixtures fixtures;
    private long accountId;
    private long detectedRunId;
    private long mappingId;
    private long issueId;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbcClient);
        accountId = fixtures.insertAdmin();
        detectedRunId = fixtures.insertCandidateRun();
        mappingId = fixtures.insertLinkMapping();
        issueId = fixtures.insertIssue(detectedRunId, mappingId, "UNMATCHED", "NEW", EVIDENCE);
    }

    @AfterEach
    void cleanUp() {
        Map<String, Object> mapping =
                jdbcClient
                        .sql(
                                """
                                select m.link_type_id, t.source_type_id, t.target_type_id
                                from link_mapping m join link_type t on t.id = m.link_type_id
                                where m.id = :id
                                """)
                        .param("id", mappingId)
                        .query()
                        .singleRow();
        jdbcClient.sql("delete from issue where id = :id").param("id", issueId).update();
        jdbcClient.sql("delete from check_run where id = :id").param("id", detectedRunId).update();
        jdbcClient.sql("delete from link_mapping where id = :id").param("id", mappingId).update();
        jdbcClient
                .sql("delete from link_type where id = :id")
                .param("id", mapping.get("link_type_id"))
                .update();
        jdbcClient
                .sql("delete from object_type where id in (:sourceId, :targetId)")
                .param("sourceId", mapping.get("source_type_id"))
                .param("targetId", mapping.get("target_type_id"))
                .update();
        jdbcClient.sql("delete from account where id = :id").param("id", accountId).update();
    }

    @Test
    void onlyOneOfConcurrentChangesWithSameRowVersionSucceeds() throws Exception {
        IssueStatusChangeRequest request =
                new IssueStatusChangeRequest("CHECKING", "동시에 확인 시작", null, 0L);
        Callable<String> change =
                () -> {
                    try {
                        IssueResponse response = statusService.change(issueId, request, admin());
                        return "OK:" + response.rowVersion();
                    } catch (BusinessException conflict) {
                        assertThat(conflict.getErrorCode().status()).isEqualTo(HttpStatus.CONFLICT);
                        return conflict.getErrorCode().code();
                    }
                };

        // 다른 연결이 행을 잡고 있는 동안 두 변경을 보낸다. 둘 다 잠금을 기다리는 것을 확인한 뒤 풀어 같은 버전으로 겨루게 한다.
        // 실패해도 잠금이 먼저 풀리도록 연결을 스레드 풀보다 먼저 닫는다(나중에 선언).
        List<String> results;
        try (var pool = Executors.newFixedThreadPool(2);
                Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock =
                    holder.prepareStatement("select id from issue where id = ? for update")) {
                lock.setLong(1, issueId);
                lock.executeQuery().close();
            }
            Future<String> first = pool.submit(change);
            Future<String> second = pool.submit(change);
            awaitLockWaiters(2);
            holder.commit();
            results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }

        assertThat(results).containsExactlyInAnyOrder("OK:1", "VERSION_CONFLICT");
        assertThat(issueRow()).containsEntry("status", "CHECKING").containsEntry("row_version", 1);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void eachCommittedChangeRaisesRowVersionByOne() {
        IssueResponse checking =
                statusService.change(
                        issueId,
                        new IssueStatusChangeRequest("CHECKING", "확인 시작", null, 0L),
                        admin());
        assertThat(checking.rowVersion()).isEqualTo(1);
        assertThat(issueRow()).containsEntry("row_version", 1);

        IssueResponse fixed =
                statusService.change(
                        issueId,
                        new IssueStatusChangeRequest("FIXED_PENDING_REVERIFY", "수정 완료", null, 1L),
                        admin());
        assertThat(fixed.rowVersion()).isEqualTo(2);
        assertThat(issueRow())
                .containsEntry("status", "FIXED_PENDING_REVERIFY")
                .containsEntry("row_version", 2);
        assertThat(historyCount()).isEqualTo(2);
    }

    /** 문제 행 잠금을 기다리는 연결이 count개가 될 때까지 기다린다. */
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting =
                    jdbcClient
                            .sql(
                                    """
                                    select count(*) from pg_stat_activity
                                    where datname = current_database()
                                      and wait_event_type = 'Lock'
                                      and query ilike '%from issue%'
                                    """)
                            .query(Long.class)
                            .single();
            if (waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("두 변경이 문제 행 잠금을 기다리지 않습니다.");
    }

    private Map<String, Object> issueRow() {
        return jdbcClient
                .sql("select status, row_version from issue where id = :id")
                .param("id", issueId)
                .query()
                .singleRow();
    }

    private long historyCount() {
        return jdbcClient
                .sql("select count(*) from issue_history where issue_id = :id")
                .param("id", issueId)
                .query(Long.class)
                .single();
    }

    private LoginAccount admin() {
        return new LoginAccount(accountId, "admin", "ADMIN", false);
    }
}
