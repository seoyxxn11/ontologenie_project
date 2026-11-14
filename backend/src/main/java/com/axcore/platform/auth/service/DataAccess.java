package com.axcore.platform.auth.service;

import com.axcore.platform.auth.repository.DataScopeRepository.ObjectTypeScope;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 한 사용자가 볼 수 있는 객체 범위(S15P31C106-205). {@link DataAccessPolicy#of}로 요청마다 만들어 그 요청 안에서만 쓴다. 객체 조회
 * API(076·077·078)와 Agent 조회 Tool이 데이터를 돌려주기 전에 같은 기준으로 확인한다.
 *
 * <ul>
 *   <li>모든 역할(ADMIN 포함)은 유형의 접근 그룹(object_type.access_group)이 역할의 허용 그룹(role_data_scope)에 있어야 본다.
 *       그룹이 없는(미분류) 유형과 허용 행이 없는 그룹은 보이지 않는다. 역할 이름으로 나누지 않으므로 역할을 늘려도 허용 행만 넣으면 된다(384).
 *   <li>민감(RESTRICTED) 값은 그 허용 행의 can_view_restricted가 true일 때만 본다.
 *   <li>읽는 용도({@link DataPurpose})가 모델이면 민감 값은 역할과 관계없이 가린다(S15P31C106-384). 정의 조회 용도로는 객체 값을 읽지
 *       않는다.
 *   <li>검사 용도({@link DataPurpose#CHECK})는 보이는 유형이 같고 민감 값을 가리지 않는다. 업무 조건 검사와 그 근거 저장에만 쓴다(404).
 * </ul>
 */
public final class DataAccess {

    private final Map<Long, ObjectTypeScope> scopes;
    private final Map<String, ObjectTypeScope> scopesByCode;
    private final DataPurpose purpose;

    DataAccess(List<ObjectTypeScope> scopes, DataPurpose purpose) {
        this.purpose = purpose;
        this.scopes =
                scopes.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ObjectTypeScope::objectTypeId, Function.identity()));
        this.scopesByCode =
                scopes.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ObjectTypeScope::objectTypeCode, Function.identity()));
    }

    /**
     * 그 유형의 그 속성 값을 가려야 하는가. RESTRICTED 속성인데 민감 값을 볼 수 없으면 true다. 객체 ID 없이 유형 코드와 속성 코드만 아는 기록(실행
     * 이력의 스냅샷 등)에 쓴다. 없는 유형이면 가릴 속성도 없으니 false다.
     */
    public boolean hidesValue(String objectTypeCode, String propertyCode) {
        ObjectTypeScope scope = scopesByCode.get(objectTypeCode);
        return scope != null
                && !canViewRestricted(scope.objectTypeId())
                && scope.restrictedPropertyCodes().contains(propertyCode);
    }

    /**
     * 그 유형의 그 속성이 민감 속성이고 보는 사람이 그 값을 볼 수 있는가. 이 값을 응답에 담으면 민감 값 열람 기록(394)을 남긴다. 없는 유형이면 false다.
     */
    public boolean revealsValue(String objectTypeCode, String propertyCode) {
        ObjectTypeScope scope = scopesByCode.get(objectTypeCode);
        return scope != null
                && canViewRestricted(scope.objectTypeId())
                && scope.restrictedPropertyCodes().contains(propertyCode);
    }

    /** 볼 수 있는 객체 유형 ID. 목록 검색은 이 값으로 SQL에서 거른다(건수에도 넣지 않는다). 비어 있으면 아무것도 보이지 않는다. */
    public Set<Long> visibleObjectTypeIds() {
        return scopes.values().stream()
                .filter(ObjectTypeScope::visible)
                .map(ObjectTypeScope::objectTypeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 없는 유형 ID면 false다. */
    public boolean canView(long objectTypeId) {
        ObjectTypeScope scope = scopes.get(objectTypeId);
        return scope != null && scope.visible();
    }

    /** 유형 코드로 확인한다. 객체 ID 없이 유형 코드로 객체를 읽는 조회(Agent 완료 시각 계산 등)가 쓴다. 없는 유형이면 false다. */
    public boolean canView(String objectTypeCode) {
        ObjectTypeScope scope = scopesByCode.get(objectTypeCode);
        return scope != null && scope.visible();
    }

    /**
     * 범위를 읽을 때 있던 유형인가. 볼 수 있는지와 관계없다. 저장된 사본(검사 표본·제안 근거)이 아직 만들지 않은 후보 유형을 가리키는지 가를 때 쓴다(403).
     */
    public boolean hasType(String objectTypeCode) {
        return scopesByCode.containsKey(objectTypeCode);
    }

    /** 볼 수 없는 유형이면 false다. 모델 용도면 항상 false, 검사 용도면 보이는 유형은 항상 true다. */
    public boolean canViewRestricted(long objectTypeId) {
        ObjectTypeScope scope = scopes.get(objectTypeId);
        if (purpose == DataPurpose.MODEL || scope == null || !scope.visible()) {
            return false;
        }
        return purpose == DataPurpose.CHECK || scope.canViewRestricted();
    }

    /** 유형 코드로 확인한다. 유형 코드만 아는 기록이 쓴다(403 저장된 사본, 404 차단 문구 가림). 없는 유형이면 false다. */
    public boolean canViewRestricted(String objectTypeCode) {
        ObjectTypeScope scope = scopesByCode.get(objectTypeCode);
        return scope != null && canViewRestricted(scope.objectTypeId());
    }

    /**
     * 그 유형의 그 속성이 민감(RESTRICTED) 속성인가. 보는 사람과 관계없다. 저장되어 여러 사람·모델이 보는 문구(재배정 차단 이유 등)에 값을 넣을지 정할 때
     * 쓴다(404). 없는 유형이면 false다.
     */
    public boolean isRestricted(String objectTypeCode, String propertyCode) {
        ObjectTypeScope scope = scopesByCode.get(objectTypeCode);
        return scope != null && scope.restrictedPropertyCodes().contains(propertyCode);
    }

    /** 이 범위를 만든 용도. */
    public DataPurpose purpose() {
        return purpose;
    }

    /**
     * 볼 수 있는 유형 중 민감 값을 가려야 하는 유형 ID. 객체 SQL이 표시 이름·원천 키를 가릴 때 쓴다. 검사 용도는 속성 값을 가리지 않지만 표시 이름이 저장되는
     * 문구에 쓰이므로, 모델 용도처럼 보이는 유형을 모두 넣는다(404). 수집 때의 표시 이름이 매핑되지 않은 원천 컬럼에서 왔을 수 있어서다.
     */
    public Set<Long> maskedObjectTypeIds() {
        if (purpose == DataPurpose.CHECK) {
            return visibleObjectTypeIds();
        }
        return scopes.values().stream()
                .filter(ObjectTypeScope::visible)
                .map(ObjectTypeScope::objectTypeId)
                .filter(id -> !canViewRestricted(id))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 객체 값을 읽어도 되는 범위인지 확인한다. 정의 조회 용도({@link DataPurpose#DEFINITIONS})는 모든 유형과 민감 속성의 정의를 보므로, 그
     * 범위로 객체 값을 읽으면 민감 값이 가려지지 않는다. 그래서 막는다.
     *
     * @throws IllegalStateException 정의 조회 용도이면
     */
    public void requireObjectValues() {
        if (purpose == DataPurpose.DEFINITIONS) {
            throw new IllegalStateException("정의 조회 용도의 범위로는 객체 값을 읽을 수 없습니다.");
        }
    }

    /** 볼 수 없는 유형의 객체는 없는 객체처럼 404로 거절한다. 객체가 있다는 사실도 알리지 않는다. */
    public void requireVisible(long objectTypeId, String resource, Object id) {
        if (!canView(objectTypeId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", resource, "id", id));
        }
    }

    /**
     * 보이는 유형마다 민감 값 열람 여부({@code restrictedView})와 민감 속성 코드({@code restricted}). 키는 유형 ID 글자다. 과거
     * 대화를 다시 쓸 때 답할 때보다 범위가 줄었는지 본다(S15P31C106-413).
     */
    public Map<String, Map<String, Object>> visibleScopes() {
        Map<String, Map<String, Object>> visible = new TreeMap<>();
        for (ObjectTypeScope scope : scopes.values()) {
            if (canView(scope.objectTypeId())) {
                visible.put(
                        Long.toString(scope.objectTypeId()),
                        Map.of(
                                "restrictedView",
                                canViewRestricted(scope.objectTypeId()),
                                "restricted",
                                List.copyOf(new TreeSet<>(scope.restrictedPropertyCodes()))));
            }
        }
        return visible;
    }

    /**
     * 민감 값을 볼 수 없으면 RESTRICTED 속성의 값을 null로 바꾸고 그 코드를 목록에 담는다. 키는 남긴다. properties에 키가 없는 속성은 가릴 값이
     * 없으니 목록에도 넣지 않는다. 원래 맵은 바꾸지 않는다. 범위를 읽을 때 없던 유형이면 가릴 속성을 모르므로 예외를 던진다.
     */
    public MaskedProperties mask(long objectTypeId, Map<String, Object> properties) {
        ObjectTypeScope scope = scopes.get(objectTypeId);
        if (scope == null) {
            throw new IllegalArgumentException("조회 범위에 없는 객체 유형입니다: " + objectTypeId);
        }
        Map<String, Object> copy = new LinkedHashMap<>(properties);
        if (canViewRestricted(objectTypeId)) {
            List<String> revealed =
                    scope.restrictedPropertyCodes().stream()
                            .filter(code -> copy.get(code) != null)
                            .sorted()
                            .toList();
            return new MaskedProperties(Collections.unmodifiableMap(copy), List.of(), revealed);
        }
        List<String> masked = new ArrayList<>();
        for (String code : scope.restrictedPropertyCodes()) {
            if (copy.containsKey(code)) {
                copy.put(code, null);
                masked.add(code);
            }
        }
        masked.sort(null);
        return new MaskedProperties(
                Collections.unmodifiableMap(copy), List.copyOf(masked), List.of());
    }
}
