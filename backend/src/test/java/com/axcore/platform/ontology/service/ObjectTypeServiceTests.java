package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.dto.DefinitionReference;
import com.axcore.platform.ontology.dto.ObjectTypeCreateRequest;
import com.axcore.platform.ontology.dto.ObjectTypeUpdateRequest;
import com.axcore.platform.ontology.repository.DefinitionReferenceRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** 객체 유형 관리(API 021~025)의 업무 규칙. DB 제약·잠금은 ObjectTypeApiTests에서 확인한다. */
@ExtendWith(MockitoExtension.class)
class ObjectTypeServiceTests {

    private static final long TYPE_ID = 3L;

    @Mock ObjectTypeRepository objectTypeRepository;

    @Mock PropertyDefinitionRepository propertyDefinitionRepository;

    @Mock DefinitionReferenceRepository referenceRepository;

    @InjectMocks ObjectTypeService objectTypeService;

    @Test
    void rejectsDuplicateCodeOnCreate() {
        given(objectTypeRepository.existsByCode("EQUIPMENT")).willReturn(true);

        assertThatThrownBy(
                        () ->
                                objectTypeService.create(
                                        new ObjectTypeCreateRequest(
                                                "EQUIPMENT", "설비", null, null, null, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT));
        verify(objectTypeRepository, never()).saveAndFlush(any());
    }

    @Test
    void createsManualTypeWithEmptySynonymsWhenOmitted() {
        given(objectTypeRepository.saveAndFlush(any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response =
                objectTypeService.create(
                        new ObjectTypeCreateRequest("EQUIPMENT", "설비", null, null, null, null));

        assertThat(response.synonyms()).isEmpty();
        assertThat(response.origin().name()).isEqualTo("MANUAL");
        assertThat(response.displayPropertyId()).isNull();
        assertThat(response.proposalItemId()).isNull();
    }

    @Test
    void rejectsStaleRowVersion() {
        givenObjectType(existing(null));

        assertThatThrownBy(() -> objectTypeService.update(TYPE_ID, rename("프레스 설비", 5L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(CommonErrorCode.VERSION_CONFLICT);
                            assertThat(ex.getDetails())
                                    .containsEntry("field", "rowVersion")
                                    .containsEntry("expected", 5L)
                                    .containsEntry("actual", 0);
                        });
    }

    @Test
    void keepsFieldsThatWereNotSent() {
        ObjectType objectType = existing(21L);
        givenObjectType(objectType);

        var response = objectTypeService.update(TYPE_ID, rename("프레스 설비", 0L));

        assertThat(response.name()).isEqualTo("프레스 설비");
        assertThat(response.description()).isEqualTo("생산 설비");
        assertThat(response.synonyms()).containsExactly("기계");
        assertThat(response.accessGroup()).isEqualTo("production");
        assertThat(response.displayPropertyId()).isEqualTo(21L);
    }

    @Test
    void clearsNullableFieldsSentAsNull() {
        givenObjectType(existing(21L));

        ObjectTypeUpdateRequest request = updateRequest(0L);
        request.setDescription(null);
        request.setAccessGroup(null);
        request.setDisplayPropertyId(null);

        var response = objectTypeService.update(TYPE_ID, request);

        assertThat(response.description()).isNull();
        assertThat(response.accessGroup()).isNull();
        assertThat(response.displayPropertyId()).isNull();
        assertThat(response.name()).isEqualTo("설비");
    }

    @Test
    void rejectsDisplayPropertyOfAnotherType() {
        givenObjectType(existing(null));
        given(propertyDefinitionRepository.existsByIdAndObjectTypeId(99L, TYPE_ID))
                .willReturn(false);

        assertThatThrownBy(() -> objectTypeService.update(TYPE_ID, displayProperty(99L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
                            assertThat(ex.getMessage()).isEqualTo("대표 속성은 같은 객체 유형의 속성이어야 합니다.");
                            assertThat(ex.getDetails()).containsEntry("field", "displayPropertyId");
                        });
    }

    @Test
    void setsDisplayPropertyOfSameType() {
        givenObjectType(existing(null));
        given(propertyDefinitionRepository.existsByIdAndObjectTypeId(21L, TYPE_ID))
                .willReturn(true);

        var response = objectTypeService.update(TYPE_ID, displayProperty(21L));

        assertThat(response.displayPropertyId()).isEqualTo(21L);
    }

    @Test
    void reportsNotFound() {
        given(objectTypeRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> objectTypeService.getObjectType(404L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                            assertThat(ex.getDetails()).containsEntry("resource", "object_type");
                        });
    }

    @Test
    void refusesToDeleteTypeInUse() {
        ObjectType objectType = existing(null);
        given(objectTypeRepository.findForUpdate(TYPE_ID)).willReturn(Optional.of(objectType));
        List<DefinitionReference> references =
                List.of(new DefinitionReference("property_definition", 3, List.of(21L, 22L, 23L)));
        given(referenceRepository.findObjectTypeReferences(TYPE_ID)).willReturn(references);

        assertThatThrownBy(() -> objectTypeService.delete(TYPE_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.IN_USE);
                            assertThat(ex.getDetails()).containsEntry("references", references);
                        });
        verify(objectTypeRepository, never()).delete(any());
    }

    @Test
    void deletesUnusedType() {
        ObjectType objectType = existing(null);
        given(objectTypeRepository.findForUpdate(TYPE_ID)).willReturn(Optional.of(objectType));
        given(referenceRepository.findObjectTypeReferences(TYPE_ID)).willReturn(List.of());

        objectTypeService.delete(TYPE_ID);

        verify(objectTypeRepository).delete(objectType);
    }

    @Test
    void searchesWithEscapedLikePattern() {
        Pageable pageable = PageRequest.of(0, 20);
        given(objectTypeRepository.search(any(), any())).willReturn(new PageImpl<>(List.of()));

        objectTypeService.search(" 50%_A\\ ", pageable);
        verify(objectTypeRepository).search("%50\\%\\_A\\\\%", pageable);

        objectTypeService.search("  ", pageable);
        verify(objectTypeRepository).search("%", pageable);
    }

    private void givenObjectType(ObjectType objectType) {
        given(objectTypeRepository.findById(TYPE_ID)).willReturn(Optional.of(objectType));
    }

    private static ObjectType existing(Long displayPropertyId) {
        ObjectType objectType =
                ObjectType.createManual("EQUIPMENT", "설비", "생산 설비", List.of("기계"), "production");
        ReflectionTestUtils.setField(objectType, "id", TYPE_ID);
        ReflectionTestUtils.setField(objectType, "displayPropertyId", displayPropertyId);
        return objectType;
    }

    private static ObjectTypeUpdateRequest updateRequest(Long rowVersion) {
        ObjectTypeUpdateRequest request = new ObjectTypeUpdateRequest();
        request.setRowVersion(rowVersion);
        return request;
    }

    private static ObjectTypeUpdateRequest rename(String name, Long rowVersion) {
        ObjectTypeUpdateRequest request = updateRequest(rowVersion);
        request.setName(name);
        return request;
    }

    private static ObjectTypeUpdateRequest displayProperty(Long propertyId) {
        ObjectTypeUpdateRequest request = updateRequest(0L);
        request.setDisplayPropertyId(propertyId);
        return request;
    }
}
