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
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 업무 개념(설비, 작업지시 등)의 정의 (FN-ONT-01). 코드는 매핑·규칙·객체가 참조하므로 만든 뒤 바꾸지 않는다. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObjectType extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> synonyms;

    private String accessGroup;

    /** 같은 유형의 속성만 가리킨다(복합 FK). 속성이 생긴 뒤 수정(API 024)에서 지정한다. */
    private Long displayPropertyId;

    @Enumerated(EnumType.STRING)
    private DefinitionOrigin origin;

    private Long proposalItemId;

    @Version private int rowVersion;

    private ObjectType(
            String code,
            String name,
            String description,
            List<String> synonyms,
            String accessGroup,
            DefinitionOrigin origin) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.synonyms = new ArrayList<>(synonyms);
        this.accessGroup = accessGroup;
        this.origin = origin;
    }

    /** 관리자가 직접 등록한 유형 (API 022). 대표 속성은 비어 있다. */
    public static ObjectType createManual(
            String code,
            String name,
            String description,
            List<String> synonyms,
            String accessGroup) {
        return new ObjectType(
                code, name, description, synonyms, accessGroup, DefinitionOrigin.MANUAL);
    }

    public void update(
            String name,
            String description,
            List<String> synonyms,
            String accessGroup,
            Long displayPropertyId) {
        this.name = name;
        this.description = description;
        this.synonyms = new ArrayList<>(synonyms);
        this.accessGroup = accessGroup;
        this.displayPropertyId = displayPropertyId;
    }
}
