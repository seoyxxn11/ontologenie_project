package com.axcore.platform.ontology.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 두 객체 유형 사이의 관계 정의 (FN-ONT-03). 연결 규칙·연결·보류 건이 생기면 코드와 양 끝 유형은 바꾸지 않는다(서비스가 검사). 업무 설명은
 * description에 둔다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkType extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    private Long sourceTypeId;

    private Long targetTypeId;

    @Enumerated(EnumType.STRING)
    private LinkCardinality cardinality;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    private DefinitionOrigin origin;

    private Long proposalItemId;

    @Version private int rowVersion;

    /** 관리자가 직접 등록한 관계 유형 (API 031). */
    @Builder(builderMethodName = "manual")
    private LinkType(
            String code,
            String name,
            Long sourceTypeId,
            Long targetTypeId,
            LinkCardinality cardinality,
            String description) {
        this.code = code;
        this.name = name;
        this.sourceTypeId = sourceTypeId;
        this.targetTypeId = targetTypeId;
        this.cardinality = cardinality;
        this.description = description;
        this.origin = DefinitionOrigin.MANUAL;
    }

    /** API 032. 코드·양 끝 유형을 바꿔도 되는지는 서비스가 먼저 확인한다. */
    public void update(
            String code,
            String name,
            Long sourceTypeId,
            Long targetTypeId,
            LinkCardinality cardinality,
            String description) {
        this.code = code;
        this.name = name;
        this.sourceTypeId = sourceTypeId;
        this.targetTypeId = targetTypeId;
        this.cardinality = cardinality;
        this.description = description;
    }
}
