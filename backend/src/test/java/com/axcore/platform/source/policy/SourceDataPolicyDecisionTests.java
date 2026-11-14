package com.axcore.platform.source.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * 운영 설정(application.yml)의 원천 데이터 정책이 결정과 같은지 고정한다(S15P31C106-384·391·405). 목록을 바꾸려면 결정을 먼저 바꾸고, 이
 * 시험과 원천 허용 목록(infra/source-db/sql/allowlist.sql, infra/tests/test_source_allowlist.py가 비교)·AI 사본
 * (ai/app/source_policy.py)을 함께 고친다.
 */
class SourceDataPolicyDecisionTests {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void applicationPolicyMatchesTheDecision() {
        assertThat(TestSourcePolicy.PROPERTIES.allowEmpty()).isFalse();
        assertThat(TestSourcePolicy.PROPERTIES.forbidden())
                .containsExactlyInAnyOrder(
                        "erp.attendance.*",
                        "erp.fixed_asset.*",
                        // 결정 D3(405): 근태 키(사번|근무일)를 담는 동기화 기록
                        "erp.change_log.*",
                        "mes.change_log.*",
                        "erp.employee.emp_name",
                        "erp.employee.position",
                        "erp.employee.hire_date",
                        "erp.employee.employment_type",
                        "erp.employee.status",
                        "mes.worker.name");
        assertThat(TestSourcePolicy.PROPERTIES.restricted())
                .containsExactlyInAnyOrder(
                        "mes.maintenance_order.technician",
                        "erp.vendor.contact_name",
                        "mes.worker.skill_level");
    }

    /**
     * 위 시험과 비교 시험들은 application.yml 원문을 읽는다. 프로필 설정이 정책을 덮어쓰면 실제로 쓰이는 목록과 달라지므로 덮어쓰지 못하게 한다(405
     * 재리뷰). 프로필 파일(main 자원과 그 config/ 아래의 application-*.yml·yaml·properties)마다 Spring처럼 묶어 보므로
     * {@code sourceDataPolicy}·{@code source_data_policy} 같은 다른 표기도 잡는다. 실제 api 컨텍스트의 목록은 {@code
     * SourcePolicyCatalogCheckIntegrationTests}가 본다. 환경변수·외부 설정은 여기서 보지 못한다(docs/data-access.md
     * 한계).
     */
    @Test
    void profileConfigsDoNotOverrideThePolicy() throws IOException {
        List<Path> profiles = profileConfigs();
        assertThat(profiles).isNotEmpty();
        for (Path profile : profiles) {
            String name = profile.getFileName().toString();
            PropertySourceLoader loader =
                    name.endsWith(".properties")
                            ? new PropertiesPropertySourceLoader()
                            : new YamlPropertySourceLoader();
            var sources = loader.load(name, new FileSystemResource(profile));
            boolean bound =
                    new Binder(ConfigurationPropertySources.from(sources))
                            .bind(
                                    "axcore.source-data-policy",
                                    Bindable.mapOf(String.class, Object.class))
                            .isBound();
            assertThat(bound).as(name + "가 원천 데이터 정책을 덮어씁니다").isFalse();
        }
    }

    /** 위 검사가 다른 표기의 덮어쓰기도 실제로 잡는지 확인한다. */
    @Test
    void overrideCheckSeesRelaxedNames() throws IOException {
        Path file = Files.createTempFile("application-relaxed", ".yml");
        try {
            Files.writeString(file, "axcore:\n  sourceDataPolicy:\n    forbidden: []\n");
            var sources =
                    new YamlPropertySourceLoader().load("relaxed", new FileSystemResource(file));
            assertThat(
                            new Binder(ConfigurationPropertySources.from(sources))
                                    .bind(
                                            "axcore.source-data-policy",
                                            Bindable.mapOf(String.class, Object.class))
                                    .isBound())
                    .isTrue();
        } finally {
            Files.delete(file);
        }
    }

    /**
     * 환경변수로 정책을 덮을 때의 이름(docs/data-access.md). 하이픈을 지운 {@code AXCORE_SOURCEDATAPOLICY_...}와 하이픈 자리에
     * 밑줄을 넣은 {@code AXCORE_SOURCE_DATA_POLICY_...} 모두 바인딩된다(2026-11-14 시험으로 확인). 둘 중 어느 쪽으로도 금지 목록을
     * 바꿀 수 있다는 뜻이라, 배포 설정에 이런 변수가 없어야 한다.
     */
    @Test
    void environmentVariableNamesForThePolicy() {
        Map<String, Object> env =
                Map.of(
                        "AXCORE_SOURCEDATAPOLICY_FORBIDDEN_0", "erp.attendance.*",
                        "AXCORE_SOURCE_DATA_POLICY_RESTRICTED_0", "erp.vendor.contact_name");
        SourceDataPolicyProperties bound =
                new Binder(
                                ConfigurationPropertySources.from(
                                        new SystemEnvironmentPropertySource(
                                                StandardEnvironment
                                                        .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                                env)))
                        .bind("axcore.source-data-policy", SourceDataPolicyProperties.class)
                        .get();

        assertThat(bound.forbidden()).containsExactly("erp.attendance.*");
        assertThat(bound.restricted()).containsExactly("erp.vendor.contact_name");
    }

    @Test
    void changeLogTablesAreForbidden() {
        assertThat(TestSourcePolicy.DEFAULT.forbidsTable("erp.change_log")).isTrue();
        assertThat(TestSourcePolicy.DEFAULT.forbidsTable("mes.change_log")).isTrue();
        assertThat(TestSourcePolicy.DEFAULT.gradeOf("erp.change_log", "row_key"))
                .isEqualTo(SourceColumnGrade.FORBIDDEN);
    }

    private static List<Path> profileConfigs() throws IOException {
        try (Stream<Path> files = Files.walk(RESOURCES, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    path.getParent().equals(RESOURCES)
                                            || path.getParent().equals(RESOURCES.resolve("config")))
                    .filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .matches("application-.+\\.(ya?ml|properties)"))
                    .toList();
        }
    }
}
