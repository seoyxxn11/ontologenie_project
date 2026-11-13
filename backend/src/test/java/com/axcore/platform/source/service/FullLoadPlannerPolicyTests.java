package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.policy.TestSourcePolicy;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 원천 데이터 정책상 들이지 않는 표·컬럼을 가리키는 켜진 매핑이 있으면 적재 계획을 만들지 않는다(391). 전체 적재와 CDC 반영이 같은 계획을 써서, 정책이 바뀐 뒤
 * Kafka에 남은 옛 이벤트도 들어오지 않는다.
 */
class FullLoadPlannerPolicyTests {

    private static final long RESOURCE_ID = 7;

    private final SourceResourceRepository resources = mock(SourceResourceRepository.class);
    private final ObjectSourceMappingRepository mappings =
            mock(ObjectSourceMappingRepository.class);
    private final PropertySourceMappingRepository propertyMappings =
            mock(PropertySourceMappingRepository.class);
    private final SourceColumnRepository columns = mock(SourceColumnRepository.class);
    private final PropertyDefinitionRepository properties =
            mock(PropertyDefinitionRepository.class);
    private final FullLoadPlanner planner =
            new FullLoadPlanner(
                    resources,
                    mappings,
                    propertyMappings,
                    columns,
                    properties,
                    mock(ObjectTypeRepository.class),
                    TestSourcePolicy.DEFAULT);

    @Test
    void refusesAWholeForbiddenTable() {
        given("erp.attendance", List.of("emp_no"), List.of());

        assertThatThrownBy(() -> planner.plan(RESOURCE_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("erp.attendance")
                .hasMessageContaining("[*]");
    }

    @Test
    void refusesForbiddenKeyAndMappedColumns() {
        given("erp.employee", List.of("emp_name"), List.of("hire_date", "dept_code"));

        assertThatThrownBy(() -> planner.plan(RESOURCE_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("emp_name")
                .hasMessageContaining("hire_date")
                .hasMessageNotContaining("dept_code");
    }

    /** 기본값 NORMAL을 분류가 끝난 것으로 보지 않는다. 민감 컬럼을 일반 속성이 담고 있으면(391 이전 매핑 등) 적재하지 않는다. */
    @Test
    void refusesRestrictedColumnHeldByANormalProperty() {
        given("mes.worker", List.of("worker_code"), List.of("skill_level"));
        PropertyDefinition skill = mock(PropertyDefinition.class);
        when(skill.getId()).thenReturn(1100L);
        when(skill.getCode()).thenReturn("skill");
        when(skill.getSensitivity()).thenReturn(PropertySensitivity.NORMAL);
        when(properties.findAllById(any())).thenReturn(List.of(skill));

        assertThatThrownBy(() -> planner.plan(RESOURCE_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("민감 컬럼")
                .hasMessageContaining("skill");
    }

    /** 민감 컬럼을 키로 쓰는 매핑(391 이전 매핑)은 원천 키로 값이 드러나므로 적재하지 않는다. */
    @Test
    void refusesRestrictedKeyColumns() {
        given("mes.maintenance_order", List.of("technician"), List.of());

        assertThatThrownBy(() -> planner.plan(RESOURCE_ID))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("키")
                .hasMessageContaining("technician");
    }

    private void given(String resourceKey, List<String> keyColumns, List<String> mappedColumns) {
        SourceResource resource = mock(SourceResource.class);
        when(resource.getResourceKey()).thenReturn(resourceKey);
        when(resource.isReadable()).thenReturn(true);
        when(resources.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        ObjectSourceMapping mapping = mock(ObjectSourceMapping.class);
        when(mapping.getId()).thenReturn(11L);
        when(mapping.getObjectTypeId()).thenReturn(12L);
        when(mapping.getKeyColumns()).thenReturn(keyColumns);
        when(mappings.findBySourceResourceIdAndActiveTrueOrderById(RESOURCE_ID))
                .thenReturn(List.of(mapping));

        List<PropertySourceMapping> mapped = new java.util.ArrayList<>();
        List<SourceColumn> sourceColumns = new java.util.ArrayList<>();
        long id = 100;
        for (String name : mappedColumns) {
            long columnId = id++;
            PropertySourceMapping propertyMapping = mock(PropertySourceMapping.class);
            when(propertyMapping.getSourceColumnId()).thenReturn(columnId);
            when(propertyMapping.getPropertyDefinitionId()).thenReturn(columnId + 1000);
            mapped.add(propertyMapping);
            SourceColumn column = mock(SourceColumn.class);
            when(column.getId()).thenReturn(columnId);
            when(column.getColumnName()).thenReturn(name);
            sourceColumns.add(column);
        }
        when(propertyMappings.findBySourceMappingIdInOrderById(any())).thenReturn(mapped);
        when(columns.findAllById(any())).thenReturn(sourceColumns);
    }
}
