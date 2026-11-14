package com.axcore.platform.source.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카탈로그 비교가 실제 카탈로그 행에서 "지금 읽을 수 있는" 표·컬럼만 보는지 확인한다(S15P31C106-405 리뷰). 연결 검사는 권한이 빠진 표를 지우지 않고
 * is_readable=false로, 권한만 빠진 컬럼은 is_present=true인 채 예전 last_seen_at으로 남긴다. 다른 시험의 데이터와 섞이지 않게 무작위
 * 스키마 이름으로 정책을 따로 만들고, 넣은 카탈로그 행은 시험 트랜잭션과 함께 되돌린다(공유 시험 DB에 남기지 않음).
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourcePolicyCatalogCheckIntegrationTests {

    @Autowired SourceResourceRepository resources;

    @Autowired SourceColumnRepository columns;

    @Autowired JdbcClient jdbc;

    @Autowired SourceColumnPolicy effective;

    /** 실제로 뜬 api 컨텍스트의 정책이 application.yml 원문(결정 시험이 고정)과 같다. 프로필 설정이 덮어쓰면 실패한다. */
    @Test
    void runningPolicyIsTheApplicationYmlPolicy() {
        assertThat(effective.forbiddenEntries())
                .isEqualTo(TestSourcePolicy.DEFAULT.forbiddenEntries())
                .contains("erp.change_log.*");
        assertThat(effective.restrictedEntries())
                .isEqualTo(TestSourcePolicy.DEFAULT.restrictedEntries());
    }

    @Test
    @Transactional
    void comparesOnlyWhatTheLastCatalogCouldRead() {
        String schema = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant seen = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant before = seen.minus(1, ChronoUnit.HOURS);
        long source =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind, connection_status)
                                values (:code, :code, 'POSTGRES', 'CONNECTED') returning id
                                """)
                        .param("code", schema)
                        .query(Long.class)
                        .single();
        // 금지 표인데 아직 읽힌다(대소문자가 섞인 이름). 금지 표였고 권한이 빠졌다.
        resource(source, schema + ".Secret", true, seen);
        resource(source, schema + ".gone", false, before);
        long person = resource(source, schema + ".person", true, seen);
        column(person, "id", seen);
        column(person, "nick", seen); // 금지 컬럼이 아직 읽힌다
        column(person, "name", before); // 금지 컬럼, 권한만 빠짐(is_present=true로 남음)
        column(person, "phone", seen); // 민감 컬럼, 읽힌다

        SourceColumnPolicy policy =
                new SourceColumnPolicy(
                        new SourceDataPolicyProperties(
                                List.of(
                                        schema + ".secret.*",
                                        schema + ".gone.*",
                                        schema + ".person.name",
                                        schema + ".person.nick"),
                                List.of(schema + ".person.phone", schema + ".person.email")));

        assertThat(new SourcePolicyCatalogCheck(policy, resources, columns).check())
                .containsExactly(
                        "금지 컬럼 " + schema + ".person.nick가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.",
                        "금지 표 " + schema + ".secret가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.",
                        "민감 항목 " + schema + ".person.email가 카탈로그에 없습니다. 표·컬럼 이름을 확인하세요.");
    }

    private long resource(long source, String key, boolean readable, Instant seen) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind, is_readable, last_seen_at)
                        values (:source, :key, 'TABLE', :readable, :seen) returning id
                        """)
                .param("source", source)
                .param("key", key)
                .param("readable", readable)
                .param("seen", Timestamp.from(seen))
                .query(Long.class)
                .single();
    }

    private void column(long resource, String name, Instant seen) {
        jdbc.sql(
                        """
                        insert into source_column (source_resource_id, column_name, source_data_type, is_nullable,
                                                   is_key_part, ordinal_position, last_seen_at)
                        values (:resource, :name, 'text', true, false,
                                (select count(*) + 1 from source_column where source_resource_id = :resource), :seen)
                        """)
                .param("resource", resource)
                .param("name", name)
                .param("seen", Timestamp.from(seen))
                .update();
    }
}
