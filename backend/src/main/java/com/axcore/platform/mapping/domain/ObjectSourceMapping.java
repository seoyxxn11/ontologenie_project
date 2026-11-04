package com.axcore.platform.mapping.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import com.axcore.platform.ontology.domain.DefinitionOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 원천 자원 한 행을 객체 하나로 만드는 규칙 (FN-MAP-02). keyColumns 값이 같은 원천 행은 같은 객체다. 객체가 만들어진 뒤에는 원천 자원·객체 유형·키
 * 컬럼을 바꿀 수 없다(DB 트리거도 막는다).
 *
 * <p>rowFilter는 지금 빈 조건({})만 쓴다(2026-11-03). 표 전체를 한 유형으로 매핑하고, 행을 나누는 값은 속성으로 둔다.
 */
@Getter
@Entity
@Table(name = "object_source_mapping")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObjectSourceMapping extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourceResourceId;

    private Long objectTypeId;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> keyColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> rowFilter;

    /** false면 이 매핑으로 수집하지 않는다. 이미 만든 객체는 남는다. */
    @Column(name = "is_active")
    private boolean active;

    @Enumerated(EnumType.STRING)
    private DefinitionOrigin origin;

    private Long proposalItemId;

    @Version private int rowVersion;

    private ObjectSourceMapping(
            Long sourceResourceId,
            Long objectTypeId,
            List<String> keyColumns,
            boolean active,
            DefinitionOrigin origin) {
        this.sourceResourceId = sourceResourceId;
        this.objectTypeId = objectTypeId;
        this.keyColumns = new ArrayList<>(keyColumns);
        this.rowFilter = new LinkedHashMap<>();
        this.active = active;
        this.origin = origin;
    }

    /** 관리자가 직접 등록한 매핑 (API 035). */
    public static ObjectSourceMapping createManual(
            Long sourceResourceId, Long objectTypeId, List<String> keyColumns, boolean active) {
        return new ObjectSourceMapping(
                sourceResourceId, objectTypeId, keyColumns, active, DefinitionOrigin.MANUAL);
    }

    /** 원천 식별(원천 자원·객체 유형·키 컬럼)이 지금과 다른지. */
    public boolean changesIdentity(
            Long sourceResourceId, Long objectTypeId, List<String> keyColumns) {
        return !this.sourceResourceId.equals(sourceResourceId)
                || !this.objectTypeId.equals(objectTypeId)
                || !this.keyColumns.equals(keyColumns);
    }

    public void changeIdentity(Long sourceResourceId, Long objectTypeId, List<String> keyColumns) {
        this.sourceResourceId = sourceResourceId;
        this.objectTypeId = objectTypeId;
        this.keyColumns = new ArrayList<>(keyColumns);
    }

    public void changeActive(boolean active) {
        this.active = active;
    }
}
