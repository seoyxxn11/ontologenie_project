package com.axcore.platform.action.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 재배정 계획 항목 (API 093·094·095의 ReassignmentItem). {@link ReassignmentPlan}처럼 조회 전용이고 저장은 {@code
 * ReassignmentPlanRepository}가 SQL로 한다. 계획 연관 관계는 두지 않고 planId로 묶어 읽는다.
 */
@Getter
@Entity
@Immutable
@Table(name = "reassignment_plan_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReassignmentPlanItem {

    @Id private Long id;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private Long workOrderId;

    /** 공정. 새 계획은 공정마다 한 항목이라 늘 값이 있다. null은 공정별로 나누기 전에 저장한 작업지시 전체 항목이다. */
    private String operationCode;

    /** 기존 설비. 계획의 정지 설비와 같다(복합 FK). */
    @Column(nullable = false)
    private Long fromEquipmentId;

    @Column(nullable = false)
    private Long toEquipmentId;

    /** 계획 시점의 원천 배정·상태. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sourceSnapshot;
}
