package com.axcore.platform.mapping.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 원천 컬럼 값을 객체 속성 하나에 넣는 규칙 (FN-MAP-03). 객체 원천 매핑 하나에 속하고, 같은 매핑에 같은 속성은 한 번만 둔다. 매핑과 컬럼이 같은 원천
 * 자원인지는 DB 복합 FK가, 속성이 매핑의 객체 유형에 속하는지는 서비스가 확인한다.
 *
 * <p>sourcePath(문서 경로)는 지금 쓰지 않는다(2026-11-03). 표 컬럼만 매핑한다.
 */
@Getter
@Entity
@Table(name = "property_source_mapping")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertySourceMapping extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourceMappingId;

    private Long sourceResourceId;

    private Long propertyDefinitionId;

    private Long sourceColumnId;

    @Column(columnDefinition = "text")
    private String sourcePath;

    /** 변환 단계 {@code [{"op": ..., "arg": ...}]}. 인자가 없는 단계도 arg 키를 null로 둔다. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> transformSteps;

    @Version private int rowVersion;

    private PropertySourceMapping(
            Long sourceMappingId,
            Long sourceResourceId,
            Long propertyDefinitionId,
            Long sourceColumnId,
            List<Map<String, Object>> transformSteps) {
        this.sourceMappingId = sourceMappingId;
        this.sourceResourceId = sourceResourceId;
        this.propertyDefinitionId = propertyDefinitionId;
        this.sourceColumnId = sourceColumnId;
        this.transformSteps = new ArrayList<>(transformSteps);
    }

    /** 관리자가 직접 등록한 속성 매핑 (API 038). */
    public static PropertySourceMapping createManual(
            Long sourceMappingId,
            Long sourceResourceId,
            Long propertyDefinitionId,
            Long sourceColumnId,
            List<Map<String, Object>> transformSteps) {
        return new PropertySourceMapping(
                sourceMappingId,
                sourceResourceId,
                propertyDefinitionId,
                sourceColumnId,
                transformSteps);
    }

    /** 보낸 값이 지금과 하나라도 다른지. 같으면 고치지 않아 버전도 그대로다. */
    public boolean differs(
            Long sourceMappingId,
            Long sourceResourceId,
            Long propertyDefinitionId,
            Long sourceColumnId,
            List<Map<String, Object>> transformSteps) {
        return !this.sourceMappingId.equals(sourceMappingId)
                || !this.sourceResourceId.equals(sourceResourceId)
                || !this.propertyDefinitionId.equals(propertyDefinitionId)
                || !Objects.equals(this.sourceColumnId, sourceColumnId)
                || !this.transformSteps.equals(transformSteps);
    }

    public void change(
            Long sourceMappingId,
            Long sourceResourceId,
            Long propertyDefinitionId,
            Long sourceColumnId,
            List<Map<String, Object>> transformSteps) {
        this.sourceMappingId = sourceMappingId;
        this.sourceResourceId = sourceResourceId;
        this.propertyDefinitionId = propertyDefinitionId;
        this.sourceColumnId = sourceColumnId;
        this.transformSteps = new ArrayList<>(transformSteps);
    }
}
