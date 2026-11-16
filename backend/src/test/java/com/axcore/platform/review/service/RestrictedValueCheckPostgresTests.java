package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.SourceTestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ReviewErrorCode;
import com.axcore.platform.review.dto.RestrictedValueRequest;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.service.CdcReloadJob;
import com.axcore.platform.source.service.RestrictedValueJob;
import com.axcore.platform.source.service.SourceCredentialService;
import com.axcore.platform.source.service.SourcePreviewQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 제안 검토 때 제한 컬럼의 값 확인(S15P31C106-447)을 실제 원천 DB로 확인한다. 접수(API) → 배치 읽기 → 결과 한 번 읽기·열람 기록·삭제.
 *
 * <p>원천 데이터 정책은 이 시험의 표로 바꿔 다른 시험의 원천 표와 섞이지 않게 한다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.source-check.ssl-mode=disable",
            "axcore.source-load.zone-id=Asia/Seoul",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE=",
            "axcore.source-data-policy.forbidden[0]=rv447.banned.*",
            "axcore.source-data-policy.forbidden[1]=rv447.vendor.secret_note",
            "axcore.source-data-policy.restricted[0]=rv447.vendor.contact_name"
        })
@ActiveProfiles("batch")
@Import(SourceTestcontainersConfiguration.class)
class RestrictedValueCheckPostgresTests {

    private static final String TABLE = "rv447.vendor";

    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired JdbcClient jdbc;
    @Autowired PostgreSQLContainer postgres;
    @Autowired SourceCredentialService credentials;
    @Autowired RestrictedValueCheckService service;
    @Autowired RestrictedValueJob job;
    @Autowired QueuedJobRunner runner;
    @Autowired SourcePreviewQueryService previewApi;

    private long resource;
    private LoginAccount admin;

    @BeforeEach
    void setUp() {
        jdbc.sql(
                        """
                        create schema if not exists rv447;
                        drop table if exists rv447.vendor;
                        create table rv447.vendor (vendor_code text, vendor_name text, contact_name text, secret_note text);
                        insert into rv447.vendor values
                          ('V1', '가나상사', '담당-A', 'n1'), ('V2', '다라상사', '담당-B', 'n2'),
                          ('V3', '마바상사', '담당-C', 'n3'), ('V4', '사아상사', '담당-D', 'n4'),
                          ('V5', '자차상사', '담당-E', 'n5'), ('V6', '카타상사', '담당-F', 'n6'),
                          ('V7', '파하상사', '담당-G', 'n7'), ('V8', '가가상사', '담당-A', 'n8'),
                          ('V9', '나나상사', null, 'n9');
                        do $$ begin
                          if not exists (select 1 from pg_roles where rolname = 'axc_rv447_sync') then
                            create role axc_rv447_sync login password 'rv447-test-only';
                          end if;
                        end $$;
                        grant usage on schema rv447 to axc_rv447_sync;
                        grant select on rv447.vendor to axc_rv447_sync;
                        """)
                .update();
        // 앞 시험이 등록한 같은 이름의 원천 테이블은 읽을 수 없는 것으로 돌린다. 원천이 여럿이면 원천마다 하나씩 나오는 것이 정상 동작이다.
        jdbc.sql("update source_resource set is_readable = false where resource_key = :key")
                .param("key", TABLE)
                .update();
        long source = registerSource();
        resource = registerTable(source);
        admin = account("ADMIN");
    }

    /**
     * 제안이 가리키는 제한 컬럼만 목록에 나오고, 확인하면 서로 다른 값 5개와 건수만 돌려준다. 키(vendor_code)나 같은 행의 다른 컬럼은 없다. 한 번 읽으면
     * 열람 기록이 남고 결과는 지워진다. 기록에는 값이 없다.
     */
    @Test
    void showsFiveDistinctValuesOnceAndRecordsTheView() {
        long proposal = proposal("PENDING");

        assertThat(service.columns(proposal, admin))
                .singleElement()
                .satisfies(
                        column -> {
                            assertThat(column.sourceResourceId()).isEqualTo(resource);
                            assertThat(column.resourceKey()).isEqualTo(TABLE);
                            assertThat(column.column()).isEqualTo("contact_name");
                        });

        var accepted =
                service.request(
                        proposal, new RestrictedValueRequest(resource, "contact_name"), admin);
        assertThat(service.result(proposal, accepted.requestId(), admin).status())
                .isEqualTo("QUEUED");
        // 원천 미리보기 조회(102)로는 이 결과를 볼 수 없다
        assertThatThrownBy(() -> previewApi.get(accepted.requestId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));

        assertThat(runner.runNext(job)).isTrue();

        var result = service.result(proposal, accepted.requestId(), admin);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.values()).containsExactly("담당-A", "담당-B", "담당-C", "담당-D", "담당-E");
        assertThat(result.totalCount()).isEqualTo(9);
        assertThat(result.emptyCount()).isEqualTo(1);
        assertThat(result.distinctCount()).isEqualTo(7);
        assertThat(result.toString()).doesNotContain("V1", "가나상사", "n1");

        List<Map<String, Object>> views =
                jdbc.sql(
                                """
                                select target_type, target_id, input::text as input, result::text as result
                                from action_execution
                                where event_kind = 'RESTRICTED_VIEW' and actor_id = :account
                                """)
                        .param("account", admin.id())
                        .query()
                        .listOfRows();
        assertThat(views)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("target_type")).isEqualTo("source_resource");
                            assertThat(((Number) row.get("target_id")).longValue())
                                    .isEqualTo(resource);
                            assertThat(row.get("result").toString())
                                    .contains("contact_name", "PROPOSAL_RESTRICTED_VALUES")
                                    .doesNotContain("담당-");
                        });
        assertThat(
                        jdbc.sql(
                                        """
                                        select input::text || coalesce(result::text, '')
                                        from action_execution
                                        where event_kind = 'SOURCE_PREVIEW' and target_id = :id
                                        """)
                                .param("id", accepted.requestId())
                                .query(String.class)
                                .list())
                .isNotEmpty()
                .allSatisfy(text -> assertThat(text).doesNotContain("담당-"));

        // 한 번 읽으면 지운다. 다시 보려면 다시 요청한다
        assertThatThrownBy(() -> service.result(proposal, accepted.requestId(), admin))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 일반 컬럼, 금지 컬럼, 제안이 가리키지 않는 컬럼은 받지 않는다. */
    @Test
    void rejectsColumnsThatAreNotRestrictedColumnsOfTheProposal() {
        long proposal = proposal("PENDING");

        for (String column : List.of("vendor_name", "secret_note", "vendor_code", "missing")) {
            assertThatThrownBy(
                            () ->
                                    service.request(
                                            proposal,
                                            new RestrictedValueRequest(resource, column),
                                            admin))
                    .as(column)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error ->
                                    assertThat(error.getErrorCode())
                                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
        long unrelated = proposalWithoutRestrictedColumn();
        assertThat(service.columns(unrelated, admin)).isEmpty();
        assertThatThrownBy(
                        () ->
                                service.request(
                                        unrelated,
                                        new RestrictedValueRequest(resource, "contact_name"),
                                        admin))
                .isInstanceOf(BusinessException.class);
    }

    /** 요청한 뒤 제안이 승인되면, 배치가 이미 읽은 결과도 내보내지 않고 지운다. 열람 기록도 남지 않는다(447 검토). */
    @Test
    void doesNotReturnValuesAfterTheProposalIsDecided() {
        long proposal = proposal("PENDING");
        var accepted =
                service.request(
                        proposal, new RestrictedValueRequest(resource, "contact_name"), admin);
        assertThat(runner.runNext(job)).isTrue();
        jdbc.sql("update proposal_item set review_status = 'APPROVED' where id = :id")
                .param("id", proposal)
                .update();

        var result = service.result(proposal, accepted.requestId(), admin);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.values()).isNull();
        assertThat(result.errorMessage()).contains("무효");
        assertThat(restrictedViews()).isZero();
        assertThat(previewRows(accepted.requestId())).isZero();
    }

    /** 요청한 뒤 제안이 개정되어 그 제한 컬럼이 빠지면, 이전 요청으로는 값을 읽지 못한다. */
    @Test
    void doesNotReturnValuesAfterTheColumnLeavesTheRevisedProposal() {
        long proposal = proposal("PENDING");
        var accepted =
                service.request(
                        proposal, new RestrictedValueRequest(resource, "contact_name"), admin);
        assertThat(runner.runNext(job)).isTrue();
        revise(
                proposal,
                """
                {"code": "VENDOR_RV", "properties": [
                  {"code": "vendor_name",
                   "sourceColumn": {"resourceKey": "rv447.vendor", "column": "vendor_name"}}]}
                """);

        var result = service.result(proposal, accepted.requestId(), admin);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.values()).isNull();
        assertThat(restrictedViews()).isZero();
        assertThat(service.columns(proposal, admin)).isEmpty();
    }

    /** 개정만 되고 컬럼이 그대로여도 요청 때의 개정본이 아니면 무효다. 다시 요청하면 새 개정본으로 읽는다. */
    @Test
    void anyRevisionInvalidatesEarlierRequests() {
        long proposal = proposal("PENDING");
        var accepted =
                service.request(
                        proposal, new RestrictedValueRequest(resource, "contact_name"), admin);
        revise(
                proposal,
                jdbc.sql("select payload::text from proposal_item where id = :id")
                        .param("id", proposal)
                        .query(String.class)
                        .single());

        // 배치도 읽기 전에 다시 본다. 무효인 요청은 원천을 읽지 않고 실패로 끝난다
        assertThat(runner.runNext(job)).isTrue();
        assertThat(
                        jdbc.sql("select status from source_preview where id = :id")
                                .param("id", accepted.requestId())
                                .query(String.class)
                                .single())
                .isEqualTo("FAILED");
        assertThat(service.result(proposal, accepted.requestId(), admin).values()).isNull();

        var again =
                service.request(
                        proposal, new RestrictedValueRequest(resource, "contact_name"), admin);
        assertThat(runner.runNext(job)).isTrue();
        assertThat(service.result(proposal, again.requestId(), admin).values()).hasSize(5);
    }

    private int restrictedViews() {
        return jdbc.sql(
                        "select count(*) from action_execution where event_kind = 'RESTRICTED_VIEW' and actor_id = :account")
                .param("account", admin.id())
                .query(Integer.class)
                .single();
    }

    private int previewRows(long id) {
        return jdbc.sql("select count(*) from source_preview where id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    /** 새 개정본을 만들고 제안의 지금 개정본으로 둔다. */
    private void revise(long proposal, String payload) {
        jdbc.sql(
                        """
                        with next as (
                            insert into proposal_revision (proposal_item_id, revision, payload, evidence, created_by)
                            select id, revision + 1, cast(:payload as jsonb), '{}'::jsonb, :admin
                            from proposal_item where id = :id
                            returning revision
                        )
                        update proposal_item
                        set revision = (select revision from next), payload = cast(:payload as jsonb), evidence = '{}'::jsonb
                        where id = :id
                        """)
                .param("id", proposal)
                .param("payload", payload)
                .param("admin", admin.id())
                .update();
    }

    /** 승인·거절된 제안은 검토가 끝났으므로 받지 않는다. */
    @Test
    void rejectsDecidedProposals() {
        for (String status : List.of("APPROVED", "REJECTED")) {
            long proposal = proposal(status);
            assertThatThrownBy(
                            () ->
                                    service.request(
                                            proposal,
                                            new RestrictedValueRequest(resource, "contact_name"),
                                            admin))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error ->
                                    assertThat(error.getErrorCode())
                                            .isEqualTo(ReviewErrorCode.PROPOSAL_ALREADY_DECIDED));
        }
    }

    /** 제안 결정 권한이 없는 역할은 목록도 확인도 못 한다. 다른 관리자가 넣은 요청의 결과도 못 읽는다. */
    @Test
    void onlyTheRequestingDeciderReadsTheResult() {
        long proposal = proposal("PENDING");
        LoginAccount viewer = account("VIEWER");
        LoginAccount planner = account("PLANNER");
        for (LoginAccount other : List.of(viewer, planner)) {
            assertThatThrownBy(() -> service.columns(proposal, other))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error ->
                                    assertThat(error.getErrorCode())
                                            .isEqualTo(CommonErrorCode.FORBIDDEN));
        }

        var accepted =
                service.request(
                        proposal, new RestrictedValueRequest(resource, "contact_name"), admin);
        assertThat(runner.runNext(job)).isTrue();
        LoginAccount otherAdmin = account("ADMIN");
        assertThatThrownBy(() -> service.result(proposal, accepted.requestId(), otherAdmin))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        // 다른 제안 번호로도 못 읽는다
        long another = proposal("PENDING");
        assertThatThrownBy(() -> service.result(another, accepted.requestId(), admin))
                .isInstanceOf(BusinessException.class);
        assertThat(service.result(proposal, accepted.requestId(), admin).values()).hasSize(5);
    }

    private long registerSource() {
        long source =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind, connection_config)
                                values (:code, '값 확인 원천', 'POSTGRES', cast(:config as jsonb))
                                returning id
                                """)
                        .param("code", "RV447-" + UUID.randomUUID())
                        .param(
                                "config",
                                """
                                {"host":"%s","port":%d,"database":"%s"}
                                """
                                        .formatted(
                                                postgres.getHost(),
                                                postgres.getMappedPort(5432),
                                                postgres.getDatabaseName()))
                        .query(Long.class)
                        .single();
        credentials.store(
                source, CredentialPurpose.SYNC, "axc_rv447_sync", "rv447-test-only", null);
        return source;
    }

    private long registerTable(long source) {
        long id =
                jdbc.sql(
                                """
                                insert into source_resource (data_source_id, resource_key, resource_kind, is_readable)
                                values (:source, :key, 'TABLE', true)
                                returning id
                                """)
                        .param("source", source)
                        .param("key", TABLE)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into source_column
                            (source_resource_id, column_name, source_data_type, ordinal_position, is_nullable)
                        values (:resource, 'vendor_code', 'text', 1, false),
                               (:resource, 'vendor_name', 'text', 2, true),
                               (:resource, 'contact_name', 'text', 3, true),
                               (:resource, 'secret_note', 'text', 4, true)
                        """)
                .param("resource", id)
                .update();
        return id;
    }

    /** 거래처 유형 후보. 담당자(제한), 이름(일반), 메모(금지), 키 컬럼을 가리킨다. */
    private long proposal(String status) {
        return insertProposal(
                status,
                """
                {"code": "VENDOR_RV", "properties": [
                  {"code": "contact_name", "sensitivity": "RESTRICTED",
                   "sourceColumn": {"resourceKey": "rv447.vendor", "column": "contact_name"}},
                  {"code": "vendor_name",
                   "sourceColumn": {"resourceKey": "rv447.vendor", "column": "vendor_name"}},
                  {"code": "secret_note",
                   "sourceColumn": {"resourceKey": "rv447.vendor", "column": "secret_note"}}]}
                """,
                """
                {"reason": "거래처 표", "properties": [{"code": "contact_name", "items": [
                  {"locator": "RV447.VENDOR.CONTACT_NAME", "values": [], "snippet": "원천 데이터 정책상 예시 값을 저장하지 않습니다."}]}],
                 "items": [{"locator": "rv447.vendor.vendor_code", "values": ["V1"]}]}
                """);
    }

    private long proposalWithoutRestrictedColumn() {
        return insertProposal(
                "PENDING",
                """
                {"code": "VENDOR_NAME_ONLY", "sourceColumn": {"resourceKey": "rv447.vendor", "column": "vendor_name"}}
                """,
                """
                {"reason": "이름만"}
                """);
    }

    private long insertProposal(String status, String payload, String evidence) {
        return jdbc.sql(
                        """
                        with added as (
                            insert into proposal_item (origin, item_kind, payload, evidence, review_status, created_by)
                            values ('MANUAL', 'OBJECT_TYPE', cast(:payload as jsonb), cast(:evidence as jsonb), :status, :admin)
                            returning id, payload, evidence
                        )
                        insert into proposal_revision (proposal_item_id, revision, payload, evidence, created_by)
                        select id, 1, payload, evidence, :admin from added
                        returning proposal_item_id
                        """)
                .param("payload", payload)
                .param("evidence", evidence)
                .param("status", status)
                .param("admin", admin.id())
                .query(Long.class)
                .single();
    }

    private LoginAccount account(String role) {
        long id =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '값 확인', 'not-used', (select id from role where code = :role), false)
                                returning id
                                """)
                        .param("login", "rv447-" + UUID.randomUUID())
                        .param("role", role)
                        .query(Long.class)
                        .single();
        return new LoginAccount(id, "rv447", role, false);
    }
}
