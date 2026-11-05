package com.axcore.platform.action.handler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 재배정 계획 Handler가 업무 조건을 검사할 때 쓰는 온톨로지 객체 유형 코드와 속성 코드. 실제 온톨로지는 AI 분석·관리자 승인으로 만들어지므로 코드에 적지 않고
 * 설정으로 둔다. 기본값은 시연 정답 온톨로지(ai/data/gold-v1.0/ontology.json)의 이름이다. 값이 빠지면 실행 중 실패하지 않도록 서버가 시작하지
 * 않는다.
 */
@Validated
@ConfigurationProperties("axcore.reassignment")
public record ReassignmentProperties(
        @NotNull @Valid Equipment equipment,
        @NotNull @Valid WorkOrder workOrder,
        @NotNull @Valid Operation operation,
        @NotNull @Valid Routing routing) {

    /** 설비. kind는 설비 종류(PRS 등), capacity는 능력(톤). */
    public record Equipment(
            @NotBlank String type,
            @NotBlank String code,
            @NotBlank String kind,
            @NotBlank String capacity) {}

    /** 작업지시. movableStatuses에 든 상태만 옮길 수 있다. */
    public record WorkOrder(
            @NotBlank String type,
            @NotBlank String number,
            @NotBlank String product,
            @NotBlank String status,
            @NotEmpty List<String> movableStatuses) {}

    /** 작업지시 공정별 배정. equipment는 배정 설비 코드, finishedStatuses에 든 공정은 옮길 수 없다. */
    public record Operation(
            @NotBlank String type,
            @NotBlank String workOrder,
            @NotBlank String sequence,
            @NotBlank String equipment,
            @NotBlank String status,
            @NotEmpty List<String> finishedStatuses) {}

    /** 제품별 공정 표준. 공정에 쓸 설비 종류와 최소 능력(톤). */
    public record Routing(
            @NotBlank String type,
            @NotBlank String product,
            @NotBlank String sequence,
            @NotBlank String equipmentKind,
            @NotBlank String minCapacity) {}
}
