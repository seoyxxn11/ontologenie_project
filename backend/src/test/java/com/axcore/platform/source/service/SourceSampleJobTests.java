package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.ResourceKind;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SourceRow;
import com.axcore.platform.source.policy.TestSourcePolicy;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository.Preview;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Sample;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

class SourceSampleJobTests {

    private static final long PREVIEW_ID = 1;
    private static final long RESOURCE_ID = 2;
    private static final long SOURCE_ID = 3;
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    private final SourcePreviewRepository previews = mock(SourcePreviewRepository.class);
    private final SourceResourceRepository resources = mock(SourceResourceRepository.class);
    private final DataSourceRepository sources = mock(DataSourceRepository.class);
    private final SourceColumnRepository columns = mock(SourceColumnRepository.class);
    private final SourceCredentialService credentials = mock(SourceCredentialService.class);
    private final SourceTableReader reader = mock(SourceTableReader.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final SourceAccount account = new SourceAccount("sample_test", "test-only");
    private final SourceSampleJob job =
            new SourceSampleJob(
                    previews,
                    resources,
                    sources,
                    columns,
                    credentials,
                    reader,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    TestSourcePolicy.DEFAULT);

    @ParameterizedTest
    @CsvSource({
        "erp.employee, emp_name",
        "erp.vendor, contact_name",
        "mes.worker, name",
        "mes.maintenance_order, technician"
    })
    /** 금지 컬럼과 민감 컬럼은 표본에서 빠진다. 표본은 원천 값을 그대로 보여 주므로 일반 컬럼만 읽는다. */
    void excludesPersonalColumnFromTheReadAndPersistedResult(String tableKey, String personal)
            throws SQLException {
        table(
                tableKey,
                List.of(
                        column("emp_no", true),
                        column(personal, true),
                        column("label", true),
                        column("removed", false)));
        Table selected = new Table(RESOURCE_ID, tableKey, List.of("emp_no", "label"), Set.of());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("emp_no", "E001");
        values.put("label", null);
        when(reader.sampleReadable(any(), eq(account), eq(selected), eq(1)))
                .thenReturn(
                        new Sample(selected.columns(), List.of(new SourceRow(values, Map.of()))));
        when(previews.finish(eq(PREVIEW_ID), any(), eq(NOW))).thenReturn(true);

        job.complete(PREVIEW_ID, job.run(PREVIEW_ID));

        verify(reader).sampleReadable(any(), eq(account), eq(selected), eq(1));
        var payload = ArgumentCaptor.forClass(String.class);
        verify(previews).finish(eq(PREVIEW_ID), payload.capture(), eq(NOW));
        var result = json.readTree(payload.getValue());
        assertThat(result.path("columns").size()).isEqualTo(2);
        assertThat(result.path("columns").get(0).asText()).isEqualTo("emp_no");
        assertThat(result.path("columns").get(1).asText()).isEqualTo("label");
        assertThat(result.path("rows").get(0).get(0).asText()).isEqualTo("E001");
        assertThat(result.path("rows").get(0).get(1).isNull()).isTrue();
        assertThat(payload.getValue()).doesNotContain("\"" + personal + "\"");
    }

    @Test
    void retainsTheSameColumnNameInAnUnrelatedEmptyTable() throws SQLException {
        table("mes.line", List.of(column("name", true)));
        Table selected = new Table(RESOURCE_ID, "mes.line", List.of("name"), Set.of());
        when(reader.sampleReadable(any(), eq(account), eq(selected), eq(1)))
                .thenReturn(new Sample(selected.columns(), List.of()));

        var result = job.run(PREVIEW_ID);
        assertThat(result.columns()).containsExactly("name");
        assertThat(result.rows()).isEmpty();
        verify(reader).sampleReadable(any(SourceEndpoint.class), eq(account), eq(selected), eq(1));
    }

    @Test
    void failsWithoutReadingWhenAllColumnsAreExcluded() {
        table("mes.worker", List.of(column("name", true)));
        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("컬럼");
        verifyNoInteractions(reader);
    }

    @Test
    void failsWithoutReadingWhenTheWholeTableIsForbidden() {
        table("erp.attendance", List.of(column("emp_no", true), column("work_date", true)));

        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("원천 데이터 정책");
        verifyNoInteractions(reader);
    }

    @ParameterizedTest
    @CsvSource({
        "28P01, 로그인하지 못했습니다",
        "28000, 로그인하지 못했습니다",
        "08006, 주소와 네트워크",
        "42501, 읽을 권한이 없습니다",
        "42P01, 테이블이나 컬럼을 찾지 못했습니다",
        "42703, 테이블이나 컬럼을 찾지 못했습니다",
        "42P09, 테이블이나 컬럼을 찾지 못했습니다",
        "57014, 시간이 너무 오래 걸려",
        "XX000, SQLSTATE XX000",
        ", SQLSTATE 없음"
    })
    void describesTheSqlFailureWithoutExposingTheDriverMessage(String sqlState, String reason)
            throws SQLException {
        table("erp.employee", List.of(column("emp_no", true)));
        when(reader.sampleReadable(any(), eq(account), any(), eq(1)))
                .thenThrow(new SQLException("driver error with source values", sqlState));

        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining(reason)
                .hasMessageNotContaining("driver error with source values")
                .hasNoCause();
    }

    @Test
    void rejectsExpiredJobBeforeLookingUpTheResource() {
        when(previews.findById(PREVIEW_ID)).thenReturn(Optional.of(previewExpiringAt(NOW)));

        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("만료");
        verifyNoInteractions(resources, reader);
    }

    @Test
    void rejectsResourceThatBecameUnreadableAfterEnqueue() {
        table("erp.employee", List.of(column("emp_no", true)));
        var resource = resources.findById(RESOURCE_ID).orElseThrow();
        when(resource.isReadable()).thenReturn(false);

        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("읽을 수 있는 원천 테이블");
        verifyNoInteractions(reader);
    }

    @Test
    void rejectsNonPostgresSourceBeforeReading() {
        table("erp.employee", List.of(column("emp_no", true)));
        var source = sources.findById(SOURCE_ID).orElseThrow();
        when(source.getSourceKind()).thenReturn(SourceKind.CSV);

        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("PostgreSQL 원천");
        verifyNoInteractions(reader);
    }

    @Test
    void failsWithoutReadingWhenSyncAccountIsMissing() {
        table("erp.employee", List.of(column("emp_no", true)));
        when(credentials.findAccount(SOURCE_ID, CredentialPurpose.SYNC))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> job.run(PREVIEW_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("SYNC 계정이 등록되지 않았습니다");
        verifyNoInteractions(reader);
    }

    @Test
    void omitsTheOriginalPersistenceError() {
        when(previews.finish(eq(PREVIEW_ID), any(), eq(NOW)))
                .thenThrow(
                        new DataIntegrityViolationException("JSON error including a source value"));

        assertThatThrownBy(
                        () ->
                                job.complete(
                                        PREVIEW_ID,
                                        new SourceSampleJob.Result(List.of("label"), List.of())))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageNotContaining("JSON error including a source value")
                .hasNoCause();
    }

    private static Preview previewExpiringAt(Instant expiresAt) {
        return new Preview(
                PREVIEW_ID,
                RESOURCE_ID,
                "SAMPLE",
                "{}",
                1,
                "RUNNING",
                9,
                NOW.minusSeconds(1),
                NOW,
                null,
                null,
                null,
                expiresAt);
    }

    private void table(String key, List<SourceColumn> availableColumns) {
        when(previews.findById(PREVIEW_ID))
                .thenReturn(Optional.of(previewExpiringAt(NOW.plusSeconds(3599))));
        SourceResource resource = mock(SourceResource.class);
        when(resource.getId()).thenReturn(RESOURCE_ID);
        when(resource.getResourceKey()).thenReturn(key);
        when(resource.getDataSourceId()).thenReturn(SOURCE_ID);
        when(resource.getResourceKind()).thenReturn(ResourceKind.TABLE);
        when(resource.isReadable()).thenReturn(true);
        when(resources.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        DataSource source = mock(DataSource.class);
        when(source.getSourceKind()).thenReturn(SourceKind.POSTGRES);
        when(source.getConnectionConfig())
                .thenReturn(Map.of("host", "localhost", "port", 5432, "database", "sample_test"));
        when(sources.findById(SOURCE_ID)).thenReturn(Optional.of(source));
        when(credentials.findAccount(SOURCE_ID, CredentialPurpose.SYNC))
                .thenReturn(Optional.of(account));
        when(columns.findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(RESOURCE_ID))
                .thenReturn(availableColumns);
    }

    private static SourceColumn column(String name, boolean present) {
        SourceColumn column = mock(SourceColumn.class);
        when(column.isPresent()).thenReturn(present);
        when(column.getColumnName()).thenReturn(name);
        return column;
    }
}
