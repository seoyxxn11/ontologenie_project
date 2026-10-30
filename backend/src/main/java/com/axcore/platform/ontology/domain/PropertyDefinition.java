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
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 객체 유형의 속성 정의 (FN-ONT-02). 코드는 객체 값(properties JSON)의 키라서 만든 뒤 바꾸지 않는다. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyDefinition extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long objectTypeId;

    private String code;

    private String name;

    @Enumerated(EnumType.STRING)
    private PropertyDataType dataType;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "is_identifier")
    private boolean identifier;

    @Column(name = "is_required")
    private boolean required;

    private String unit;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> constraints;

    @Enumerated(EnumType.STRING)
    private PropertySensitivity sensitivity;

    @Enumerated(EnumType.STRING)
    private TemporalRole temporalRole;

    @Enumerated(EnumType.STRING)
    private DefinitionOrigin origin;

    private Long proposalItemId;

    @Version private int rowVersion;

    /** 관리자가 직접 등록한 속성 (API 027). */
    @Builder(builderMethodName = "manual")
    private PropertyDefinition(
            Long objectTypeId,
            String code,
            String name,
            PropertyDataType dataType,
            String description,
            boolean identifier,
            boolean required,
            String unit,
            Map<String, Object> constraints,
            PropertySensitivity sensitivity,
            TemporalRole temporalRole) {
        this.objectTypeId = objectTypeId;
        this.code = code;
        this.name = name;
        this.dataType = dataType;
        this.description = description;
        this.identifier = identifier;
        this.required = required;
        this.unit = unit;
        this.constraints =
                constraints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(constraints);
        this.sensitivity = sensitivity == null ? PropertySensitivity.NORMAL : sensitivity;
        this.temporalRole = temporalRole;
        this.origin = DefinitionOrigin.MANUAL;
    }

    /** 코드·소속 유형·출처를 뺀 값을 바꾼다 (API 028). 기존 값과 맞는지는 서비스가 먼저 확인한다. */
    public void update(
            String name,
            PropertyDataType dataType,
            String description,
            boolean identifier,
            boolean required,
            String unit,
            Map<String, Object> constraints,
            PropertySensitivity sensitivity,
            TemporalRole temporalRole) {
        this.name = name;
        this.dataType = dataType;
        this.description = description;
        this.identifier = identifier;
        this.required = required;
        this.unit = unit;
        this.constraints = new LinkedHashMap<>(constraints);
        this.sensitivity = sensitivity;
        this.temporalRole = temporalRole;
    }
}
