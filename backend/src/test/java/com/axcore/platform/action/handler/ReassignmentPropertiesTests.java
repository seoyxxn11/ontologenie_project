package com.axcore.platform.action.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** 재배정 설정이 빠지면 실행 중 실패하지 않도록 서버 시작 단계에서 막는다. */
class ReassignmentPropertiesTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                    .withUserConfiguration(Config.class);

    @Test
    void failsToStartWhenSettingIsMissing() {
        runner.withPropertyValues(
                        "axcore.reassignment.equipment.type=Equipment",
                        "axcore.reassignment.equipment.code=code",
                        "axcore.reassignment.equipment.kind=kind")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithCompleteSettings() {
        runner.withPropertyValues(
                        "axcore.reassignment.equipment.type=Equipment",
                        "axcore.reassignment.equipment.code=code",
                        "axcore.reassignment.equipment.kind=kind",
                        "axcore.reassignment.equipment.capacity=capacity_ton",
                        "axcore.reassignment.work-order.type=WorkOrder",
                        "axcore.reassignment.work-order.number=wo_no",
                        "axcore.reassignment.work-order.product=product_code",
                        "axcore.reassignment.work-order.status=status",
                        "axcore.reassignment.work-order.movable-statuses=대기,진행",
                        "axcore.reassignment.operation.type=WorkOrderOperation",
                        "axcore.reassignment.operation.work-order=wo_no",
                        "axcore.reassignment.operation.sequence=op_seq",
                        "axcore.reassignment.operation.equipment=equipment_code",
                        "axcore.reassignment.operation.status=status",
                        "axcore.reassignment.operation.finished-statuses=완료",
                        "axcore.reassignment.routing.type=Routing",
                        "axcore.reassignment.routing.product=product_code",
                        "axcore.reassignment.routing.sequence=op_seq",
                        "axcore.reassignment.routing.equipment-kind=equipment_kind",
                        "axcore.reassignment.routing.min-capacity=min_tonnage")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReassignmentProperties.class)
    static class Config {}
}
