package com.axcore.platform.source.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 원천 데이터 정책이 닫힌 기본값인지 확인한다(S15P31C106-405). 정책 섹션이 없거나 비면, 또는 항목 형식이 틀리면 컨텍스트가 뜨지 않고, {@code
 * allow-empty: true}로 명시했을 때만 빈 정책으로 뜬다.
 */
class SourceDataPolicyStartupTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SourceColumnPolicy.class);

    @Test
    void doesNotStartWithoutPolicySection() {
        runner.run(
                context ->
                        assertThat(context)
                                .hasFailed()
                                .getFailure()
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("금지 목록(forbidden)이 없거나 비어 있습니다")
                                .hasMessageContaining("allow-empty=true"));
    }

    @Test
    void doesNotStartWithEmptyLists() {
        runner.withPropertyValues(
                        "axcore.source-data-policy.forbidden=",
                        "axcore.source-data-policy.restricted=")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("금지 목록(forbidden)이 없거나 비어 있습니다"));
    }

    /** 민감 목록만 남고 금지 목록이 비어도(프로필·환경변수로 금지만 비운 경우) 금지 데이터가 모두 열리므로 뜨지 않는다(405 재리뷰). */
    @Test
    void doesNotStartWithRestrictedListOnly() {
        runner.withPropertyValues(
                        "axcore.source-data-policy.forbidden=",
                        "axcore.source-data-policy.restricted[0]=erp.vendor.contact_name")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("금지 목록(forbidden)이 없거나 비어 있습니다"));
    }

    @Test
    void startsWithEmptyPolicyOnlyWhenExplicitlyAllowed() {
        runner.withPropertyValues("axcore.source-data-policy.allow-empty=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(SourceColumnPolicy.class)
                                                    .gradeOf("erp.attendance", "emp_no"))
                                    .isEqualTo(SourceColumnGrade.NORMAL);
                        });
    }

    /** {@code .*}를 빠뜨린 표 항목은 어떤 컬럼에도 맞지 않아 조용히 열리므로 기동을 멈춘다. */
    @Test
    void doesNotStartWithMalformedEntry() {
        runner.withPropertyValues(
                        "axcore.source-data-policy.forbidden[0]=erp.attendance",
                        "axcore.source-data-policy.restricted[0]=erp.vendor.contact_name")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("forbidden[erp.attendance]"));
    }

    @Test
    void startsWithWellFormedPolicy() {
        runner.withPropertyValues(
                        "axcore.source-data-policy.forbidden[0]=erp.attendance.*",
                        "axcore.source-data-policy.restricted[0]=erp.vendor.contact_name")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(SourceColumnPolicy.class)
                                                    .forbidsTable("erp.attendance"))
                                    .isTrue();
                        });
    }
}
