package com.axcore.platform.source.policy;

import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.mapping.service.MappedResourceReload;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.repository.PropertyValueRepository;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.service.SourceCatalogService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원천 데이터 정책을 강화한 뒤 이미 저장된 값을 정책에 맞춘다(S15P31C106-430).
 *
 * <p>정책은 설정이라 바꾸면 재기동한다. 매핑 저장 때만 속성을 민감으로 올리고 화면·Agent의 가림은 속성 민감도만 보므로, 그대로 두면 새로 민감·금지가 된 컬럼을 담던
 * 일반 속성의 값이 모든 역할과 모델에 계속 나간다. 그래서 기동할 때 매핑(켜짐·꺼짐 모두)을 정책과 견준다.
 *
 * <ul>
 *   <li>민감 컬럼을 담는 일반 속성은 민감으로 올린다. 값은 남고 민감 값을 볼 수 없는 역할과 모델에는 가려진다.
 *   <li>금지 컬럼을 담는 속성은 민감으로 올리고 객체의 그 값을 지운다. 금지 값은 플랫폼에 있으면 안 된다. 매핑은 남아 그 표의 적재가 멈추므로 관리자가 매핑을
 *       지운다.
 *   <li>올리면 켜진 매핑의 표를 다시 읽기로 표시한다(속성 수정 028과 같다). 잠금 순서는 원천 자원 → 속성 정의로 매핑 변경·수집과 같다.
 * </ul>
 *
 * <p>키·식별·대표 속성이나 연결 조건에 쓰던 속성도 올린다. 그 기능이 가려진 값으로 동작하는 쪽(닫힌 쪽)이 값이 새는 것보다 낫다. 백엔드와 batch가 함께 기동해도
 * 잠근 뒤 다시 보므로 한 번만 바뀐다.
 */
@Slf4j
@Component
public class SourcePolicyReconciler {

    private final SourceColumnPolicy policy;
    private final PropertySourceMappingRepository propertyMappings;
    private final SourceColumnRepository columns;
    private final SourceResourceRepository resources;
    private final PropertyDefinitionRepository properties;
    private final PropertyValueRepository propertyValues;
    private final MappedResourceReload reload;
    private final ObjectTypeRepository objectTypes;
    private final ObjectSourceMappingRepository objectMappings;
    private final SourceCatalogService catalog;
    private final TransactionTemplate transaction;

    public SourcePolicyReconciler(
            SourceColumnPolicy policy,
            PropertySourceMappingRepository propertyMappings,
            SourceColumnRepository columns,
            SourceResourceRepository resources,
            PropertyDefinitionRepository properties,
            PropertyValueRepository propertyValues,
            MappedResourceReload reload,
            ObjectTypeRepository objectTypes,
            ObjectSourceMappingRepository objectMappings,
            SourceCatalogService catalog,
            PlatformTransactionManager transactionManager) {
        this.policy = policy;
        this.propertyMappings = propertyMappings;
        this.columns = columns;
        this.resources = resources;
        this.properties = properties;
        this.propertyValues = propertyValues;
        this.reload = reload;
        this.objectTypes = objectTypes;
        this.objectMappings = objectMappings;
        this.catalog = catalog;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 원천 카탈로그 확인(SourcePolicyCatalogCheck) 뒤에 따로 돈다. 잠금을 기다릴 수 있어 기동(ACCEPTING_TRAFFIC)을 붙잡지 않게 별도
     * 스레드에서 돌린다. 실패해도 기동은 막지 않고 오류로 남긴다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void reconcileOnStartup() {
        Thread.ofVirtual()
                .name("source-policy-reconcile")
                .start(
                        () -> {
                            try {
                                reconcile().forEach(change -> log.warn("원천 데이터 정책 적용: {}", change));
                            } catch (RuntimeException e) {
                                log.error(
                                        "원천 데이터 정책을 저장된 속성에 적용하지 못했습니다. 정책 보고(SourcePolicyReport)로 확인하세요.",
                                        e);
                            }
                        });
    }

    /** 바꾼 속성마다 한 문장. 바꿀 것이 없으면 빈 목록. 속성마다 따로 커밋하고, 한 속성이 실패해도 나머지는 계속한다. */
    public List<String> reconcile() {
        List<String> changes = new ArrayList<>();
        findingsByProperty()
                .forEach(
                        (propertyId, finding) -> {
                            try {
                                transaction
                                        .execute(status -> apply(propertyId, finding))
                                        .ifPresent(changes::add);
                            } catch (RuntimeException e) {
                                log.error(
                                        "원천 데이터 정책을 속성 {}에 적용하지 못했습니다. 다음 기동 때 다시 봅니다.",
                                        propertyId,
                                        e);
                            }
                        });
        return changes;
    }

    /** 정책 항목 컬럼을 담는 매핑. grade는 가장 엄한 등급(금지 > 민감), forbiddenMappings는 금지 컬럼을 담는 객체 매핑 ID. */
    private record Finding(
            SourceColumnGrade grade, Set<Long> forbiddenMappings, Set<Long> forbiddenResources) {}

    private Map<Long, Finding> findingsByProperty() {
        Map<Long, Finding> findings = new TreeMap<>();
        for (PropertySourceMapping mapping : propertyMappings.findAll()) {
            SourceColumnGrade grade = gradeOf(mapping);
            if (grade == SourceColumnGrade.NORMAL) {
                continue;
            }
            Finding before =
                    findings.getOrDefault(
                            mapping.getPropertyDefinitionId(),
                            new Finding(SourceColumnGrade.RESTRICTED, Set.of(), Set.of()));
            Set<Long> forbidden = new TreeSet<>(before.forbiddenMappings());
            Set<Long> forbiddenResources = new TreeSet<>(before.forbiddenResources());
            if (grade == SourceColumnGrade.FORBIDDEN) {
                forbidden.add(mapping.getSourceMappingId());
                forbiddenResources.add(mapping.getSourceResourceId());
            }
            findings.put(
                    mapping.getPropertyDefinitionId(),
                    new Finding(
                            forbidden.isEmpty()
                                    ? SourceColumnGrade.RESTRICTED
                                    : SourceColumnGrade.FORBIDDEN,
                            forbidden,
                            forbiddenResources));
        }
        return findings;
    }

    private SourceColumnGrade gradeOf(PropertySourceMapping mapping) {
        if (mapping.getSourceColumnId() == null) {
            return SourceColumnGrade.NORMAL;
        }
        SourceColumnRepository.ColumnLocation column =
                columns.findLocationById(mapping.getSourceColumnId()).orElse(null);
        if (column == null) {
            return SourceColumnGrade.NORMAL;
        }
        return resources
                .findResourceKey(column.getSourceResourceId())
                .map(key -> policy.gradeOf(key, column.getColumnName()))
                .orElse(SourceColumnGrade.NORMAL);
    }

    private Optional<String> apply(long propertyId, Finding finding) {
        PropertySensitivity before = properties.findSensitivity(propertyId).orElse(null);
        Long typeId = properties.findObjectTypeId(propertyId).orElse(null);
        if (before == null || typeId == null) {
            return Optional.empty();
        }
        boolean forbidden = finding.grade() == SourceColumnGrade.FORBIDDEN;
        boolean raise = before != PropertySensitivity.RESTRICTED;
        boolean display =
                objectTypes.findDisplayPropertyId(typeId).map(id -> id == propertyId).orElse(false);
        if (!raise && !forbidden && !display) {
            return Optional.empty();
        }
        // 매핑 변경·수집과 같은 순서로 원천 자원을 먼저 ID 순으로 잠그고 속성을 잠근다. 바꿀 객체 행을 쓰는 표를 모두 잠근다: 올리면 이 속성을 담는
        // 켜진 매핑의 표(다시 읽기 표시), 금지 값을 지우면 그 금지 매핑의 표, 대표에서 빼면 유형 전체 표시 이름을 바꾸므로 유형의 켜진 매핑 표 전부.
        // 잠그기 전에는 엔티티를 불러오지 않아 아래 잠금 조회가 최신 행을 읽는다(429).
        Set<Long> reloadIds = raise ? reload.activeResourceIds(propertyId) : Set.of();
        Set<Long> lockIds = new TreeSet<>(reloadIds);
        if (forbidden) {
            lockIds.addAll(finding.forbiddenResources());
        }
        if (display) {
            lockIds.addAll(objectMappings.findActiveSourceResourceIds(typeId));
        }
        Map<Long, SourceResource> locked = new TreeMap<>();
        for (Long id : lockIds) {
            catalog.lockResource(id).ifPresent(resource -> locked.put(id, resource));
        }
        PropertyDefinition property = properties.findForUpdate(propertyId).orElse(null);
        if (property == null) {
            return Optional.empty();
        }
        boolean raised = property.getSensitivity() != PropertySensitivity.RESTRICTED;
        if (raised) {
            property.restrictBySourcePolicy();
            reload.requireFullReload(
                    reloadIds.stream().map(locked::get).filter(Objects::nonNull).toList());
        }
        int cleared = 0;
        if (forbidden) {
            // 금지 컬럼을 담던 매핑이 만든 객체에서만 지운다. 같은 속성을 다른 표의 일반 컬럼에서 채운 객체는 둔다.
            cleared =
                    propertyValues.clearValues(
                            property.getObjectTypeId(),
                            property.getCode(),
                            finding.forbiddenMappings());
        }
        // 민감·금지 컬럼을 담는 속성은 대표 속성이 될 수 없다(매핑 저장 038과 같은 규칙). 이미 민감이어도 대표로 남았으면 뺀다.
        boolean displayReset = display && dropDisplayProperty(property);
        if (!raised && cleared == 0 && !displayReset) {
            return Optional.empty();
        }
        return Optional.of(
                "속성 %d(%s, 유형 %d)%s%s%s"
                        .formatted(
                                property.getId(),
                                property.getCode(),
                                property.getObjectTypeId(),
                                raised ? " 민감으로 올림" : "",
                                forbidden ? " · 금지 컬럼 값 " + cleared + "개 객체에서 지움 · 매핑을 지우세요" : "",
                                displayReset ? " · 대표 속성에서 빼고 표시 이름을 원천 키로 바꿈" : ""));
    }

    /**
     * 속성이 유형의 대표 속성이면 대표에서 빼고, 그 유형 모든 객체의 표시 이름을 원천 키로 바꾼다. 꺼진 매핑의 객체는 다시 읽지 않아 표시 이름에 민감·금지 값이 남기
     * 때문이다.
     */
    private boolean dropDisplayProperty(PropertyDefinition property) {
        ObjectType type = objectTypes.findById(property.getObjectTypeId()).orElse(null);
        if (type == null || !property.getId().equals(type.getDisplayPropertyId())) {
            return false;
        }
        type.update(
                type.getName(),
                type.getDescription(),
                type.getSynonyms(),
                type.getAccessGroup(),
                null);
        propertyValues.resetDisplayNames(property.getObjectTypeId());
        return true;
    }
}
