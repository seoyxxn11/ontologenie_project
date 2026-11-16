package com.axcore.platform.ontology.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.audit.service.RestrictedViewRecorder.Seen;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.auth.service.MaskedProperties;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.dto.ObjectLinkGroupResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.dto.ObjectSearchAroundRequest;
import com.axcore.platform.ontology.dto.ObjectSearchAroundResult;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectQueryRepository;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.DetailRow;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.ObjectRow;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.PropertyFilter;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.SearchFilters;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.SortOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ObjectQueryService
        implements ObjectSearchQuery, ObjectDetailQuery, ObjectEvidenceQuery {

    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 공백만 다른 저장 값은 하나인지만 알면 된다. 같은 값이 섞여 있어도 판단할 수 있게 넉넉히 읽는다. */
    private static final int MAX_SPACING_VARIANTS = 10;

    /** Search Around(379)의 기준 객체 수와 결과 건수 상한. Agent가 한 번에 다룰 객체 집합의 크기다. */
    static final int SEARCH_AROUND_LIMIT = 5000;

    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectQueryRepository objects;
    private final LinkTypeRepository linkTypes;
    private final ObjectSearchFilterValidator filterValidator;
    private final RestrictedViewRecorder restrictedViews;

    // 권한, 목록, 전체 건수를 같은 스냅샷에서 읽는다.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectSearchResponse> search(LoginAccount actor, ObjectSearchRequest request) {
        return search(actor, DataPurpose.VIEW, request);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectSearchResponse> search(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request) {
        return search(actor, purpose, request, Extras.NONE);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectSearchResponse> search(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, Extras extras) {
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        SearchScope scope = searchScope(actor, purpose, request, extras);
        if (scope == null) {
            return Page.empty(page);
        }
        String q = request.q();
        String exactPattern = q == null || q.isBlank() ? null : SearchPattern.exact(q);
        List<Seen> seen = new ArrayList<>();
        Page<ObjectSearchResponse> found =
                objects.search(
                                scope.visibleTypeIds(),
                                maskedTypes(scope.access(), scope.visibleTypeIds()),
                                scope.status(),
                                exactPattern,
                                scope.filters(),
                                scope.sort(),
                                page)
                        .map(row -> toResponse(row, scope.access(), seen));
        restrictedViews.record(actor, "OBJECT_SEARCH", seen);
        return found;
    }

    // 건수와 ID를 같은 스냅샷에서 읽는다.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IdSearch searchIds(LoginAccount actor, ObjectSearchRequest request, int limit) {
        return searchIds(actor, DataPurpose.VIEW, request, limit);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IdSearch searchIds(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, int limit) {
        return searchIds(actor, purpose, request, Extras.NONE, limit);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IdSearch searchIds(
            LoginAccount actor,
            DataPurpose purpose,
            ObjectSearchRequest request,
            Extras extras,
            int limit) {
        if (request.q() != null && !request.q().isBlank()) {
            throw new IllegalArgumentException("검색어 조건은 ID 조회에서 지원하지 않습니다.");
        }
        SearchScope scope = searchScope(actor, purpose, request, extras);
        if (scope == null) {
            return new IdSearch(0, List.of());
        }
        long total = objects.count(scope.visibleTypeIds(), scope.status(), scope.filters());
        List<Long> ids =
                total == 0 || total > limit
                        ? List.of()
                        : objects.searchIds(
                                scope.visibleTypeIds(), scope.status(), scope.filters(), limit);
        return new IdSearch(total, ids);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, BigDecimal> sums(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, Extras extras) {
        if (request.objectTypeId() == null) {
            throw BusinessException.invalidField("objectTypeId", "합계에는 객체 유형을 지정해 주세요.");
        }
        if (request.q() != null && !request.q().isBlank()) {
            throw new IllegalArgumentException("검색어 조건은 합계에서 지원하지 않습니다.");
        }
        SearchScope scope = searchScope(actor, purpose, request, extras);
        if (scope == null) {
            return Map.of();
        }
        return objects.sums(
                scope.visibleTypeIds(),
                scope.status(),
                scope.filters(),
                filterValidator.summableProperties(request.objectTypeId(), scope.access()));
    }

    /**
     * 076의 권한·조건 검사. 볼 수 있는 유형이 없으면 조건을 검증하지 않고 null이다(숨긴 유형의 속성을 드러내지 않는다). 값 목록 조건(Agent 전용)이 있으면
     * Search Around와 같은 검사(validateAny)로 값 하나 조건과 함께 검증한다.
     */
    private SearchScope searchScope(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, Extras extras) {
        Extras agent = extras == null ? Extras.NONE : extras;
        Map<String, List<String>> propertyValueSets = agent.propertyValueSets();
        boolean severalValues = !propertyValueSets.isEmpty();
        boolean agentOnly = severalValues || !agent.comparisons().isEmpty() || agent.sort() != null;
        if ((request.hasFilters() || agentOnly) && request.objectTypeId() == null) {
            throw BusinessException.invalidField("objectTypeId", "속성 또는 기간 조건에는 객체 유형을 지정해 주세요.");
        }
        Status status = request.status() == null ? Status.ACTIVE : request.status();
        if (status == Status.DELETED_AT_SOURCE && !RoleCode.ADMIN.name().equals(actor.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN,
                    "원천에서 삭제된 객체는 관리자만 조회할 수 있습니다.",
                    Map.of("field", "status", "requiredPermission", RoleCode.ADMIN.name()));
        }
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (request.objectTypeId() != null) {
            visibleTypeIds =
                    visibleTypeIds.contains(request.objectTypeId())
                            ? Set.of(request.objectTypeId())
                            : Set.of();
        }
        if (visibleTypeIds.isEmpty()) {
            return null;
        }
        SearchFilters filters =
                severalValues
                        ? filterValidator.validateAny(
                                request.objectTypeId(),
                                mergedConditions(request.propertyValues(), propertyValueSets),
                                request.dateProperty(),
                                request.from(),
                                request.to(),
                                access)
                        : filterValidator.validate(request, access);
        if (!filters.values().isEmpty()) {
            filters = matchStoredSpacing(filters, request.objectTypeId(), status);
        }
        SortOrder sort = null;
        if (agentOnly) {
            filters =
                    new SearchFilters(
                            filters.values(),
                            filters.period(),
                            filterValidator.validateComparisons(
                                    request.objectTypeId(), agent.comparisons(), access));
            sort = filterValidator.validateSort(request.objectTypeId(), agent.sort(), access);
        }
        return new SearchScope(access, visibleTypeIds, status, filters, sort);
    }

    /** 값 하나 조건과 값 목록 조건을 합친다. 같은 속성을 두 칸에 함께 넣으면 입력 오류다(Search Around와 같다). */
    private static Map<String, List<String>> mergedConditions(
            Map<String, String> single, Map<String, List<String>> several) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        single.forEach((code, value) -> conditions.put(code, Collections.singletonList(value)));
        several.forEach(
                (code, values) -> {
                    if (conditions.putIfAbsent(code, values) != null) {
                        throw BusinessException.invalidField(
                                "propertyValueSets[" + code + "]",
                                "같은 속성을 propertyValues와 propertyValueSets에 함께 넣을 수 없습니다.");
                    }
                });
        return conditions;
    }

    private record SearchScope(
            DataAccess access,
            Set<Long> visibleTypeIds,
            Status status,
            SearchFilters filters,
            SortOrder sort) {}

    /**
     * 문자 조건 값과 똑같은 저장 값이 없고 공백만 다른 저장 값이 하나뿐이면 그 값으로 거른다(378). "정비 중"으로 물으면 저장 값 "정비중"을 찾는다. 같은 값이
     * 있으면 그대로 두고, 공백만 다른 값이 여럿이면 어느 것인지 정할 수 없어 그대로 둔다(0건).
     */
    private SearchFilters matchStoredSpacing(SearchFilters filters, long typeId, Status status) {
        List<PropertyFilter> matched = new ArrayList<>();
        for (PropertyFilter filter : filters.values()) {
            if (filter.type() != PropertyDataType.STRING) {
                matched.add(filter);
                continue;
            }
            List<String> values = new ArrayList<>();
            for (String value : filter.values()) {
                List<String> stored =
                        objects.findSpacingVariants(
                                typeId, filter.code(), value, status, MAX_SPACING_VARIANTS);
                boolean replace = stored.size() == 1 && !stored.contains(value);
                values.add(replace ? stored.getFirst() : value);
            }
            matched.add(new PropertyFilter(filter.code(), filter.type(), values));
        }
        return new SearchFilters(List.copyOf(matched), filters.period(), filters.comparisons());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectSearchResponse> findByPropertyValue(
            LoginAccount actor, String value, int limit) {
        return findByPropertyValue(actor, DataPurpose.VIEW, value, limit);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectSearchResponse> findByPropertyValue(
            LoginAccount actor, DataPurpose purpose, String value, int limit) {
        String exact = value == null ? "" : value.strip();
        if (exact.isEmpty()) {
            return List.of();
        }
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty()) {
            return List.of();
        }
        List<Seen> seen = new ArrayList<>();
        List<ObjectSearchResponse> found =
                objects
                        .findByPropertyValue(
                                visibleTypeIds, maskedTypes(access, visibleTypeIds), exact, limit)
                        .stream()
                        .map(row -> toResponse(row, access, seen))
                        .toList();
        restrictedViews.record(actor, "OBJECT_VALUE_SEARCH", seen);
        return found;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectSearchResponse> findByIds(LoginAccount actor, Collection<Long> ids) {
        return findByIds(actor, DataPurpose.VIEW, ids);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectSearchResponse> findByIds(
            LoginAccount actor, DataPurpose purpose, Collection<Long> ids) {
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("객체 ID는 한 번에 " + MAX_IDS + "개까지 읽을 수 있습니다.");
        }
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (ids.isEmpty() || visibleTypeIds.isEmpty()) {
            return List.of();
        }
        List<Seen> seen = new ArrayList<>();
        List<ObjectSearchResponse> found =
                objects.findByIds(ids, visibleTypeIds, maskedTypes(access, visibleTypeIds)).stream()
                        .map(row -> toResponse(row, access, seen))
                        .toList();
        restrictedViews.record(actor, "OBJECT_BY_IDS", seen);
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findAvailableIds(LoginAccount actor, Collection<Long> ids) {
        Set<Long> visibleTypeIds = dataAccessPolicy.of(actor).visibleObjectTypeIds();
        if (ids.isEmpty() || visibleTypeIds.isEmpty()) {
            return List.of();
        }
        Set<Long> available = objects.findActiveObjectTypes(ids, visibleTypeIds).keySet();
        return ids.stream().distinct().filter(available::contains).toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectDetailResponse detail(LoginAccount actor, long objectId) {
        return detail(actor, DataPurpose.VIEW, objectId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectDetailResponse detail(LoginAccount actor, DataPurpose purpose, long objectId) {
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty()) {
            throw notFound(objectId);
        }
        DetailRow detail =
                objects.findDetail(
                                objectId,
                                visibleTypeIds,
                                maskedTypes(access, visibleTypeIds),
                                RoleCode.ADMIN.name().equals(actor.roleCode()))
                        .orElseThrow(() -> notFound(objectId));
        ObjectRow row = detail.object();
        MaskedProperties masked = access.mask(row.objectTypeId(), row.properties());
        restrictedViews.record(
                actor,
                "OBJECT_DETAIL",
                List.of(Seen.object(row.id(), masked.revealedPropertyCodes())));
        return new ObjectDetailResponse(
                row.id(),
                row.objectTypeId(),
                row.objectTypeName(),
                row.displayName(),
                masked.properties(),
                detail.propertyNames(),
                masked.restrictedPropertyCodes(),
                row.status(),
                row.sourceMappingId(),
                detail.sourceSystemName(),
                detail.sourceResourceKey(),
                row.sourceKey(),
                row.firstCollectedAt(),
                row.lastSyncedAt(),
                dataAsOf(row.asOfCode(), masked.properties()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectLinkResponse> links(
            LoginAccount actor, long objectId, ObjectLinkSearchRequest request) {
        return links(actor, DataPurpose.VIEW, objectId, request);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectLinkResponse> links(
            LoginAccount actor,
            DataPurpose purpose,
            long objectId,
            ObjectLinkSearchRequest request) {
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty() || !objects.existsActive(objectId, visibleTypeIds)) {
            throw notFound(objectId);
        }
        Direction direction = request.direction() == null ? Direction.BOTH : request.direction();
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return objects.links(
                objectId,
                visibleTypeIds,
                maskedTypes(access, visibleTypeIds),
                request.linkTypeId(),
                direction,
                page);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectLinkGroupResponse> linkGroups(LoginAccount actor, long objectId) {
        return linkGroups(actor, DataPurpose.VIEW, objectId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectLinkGroupResponse> linkGroups(
            LoginAccount actor, DataPurpose purpose, long objectId) {
        Set<Long> visibleTypeIds = dataAccessPolicy.of(actor, purpose).visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty() || !objects.existsActive(objectId, visibleTypeIds)) {
            throw notFound(objectId);
        }
        return objects.linkGroups(objectId, visibleTypeIds);
    }

    // 기준 객체 확인, 조건 검증, 한 홉 조회를 같은 스냅샷에서 한다.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectSearchAroundResult searchAround(
            LoginAccount actor, ObjectSearchAroundRequest request) {
        return searchAround(actor, DataPurpose.VIEW, request);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectSearchAroundResult searchAround(
            LoginAccount actor, DataPurpose purpose, ObjectSearchAroundRequest request) {
        List<Long> baseIds = baseObjectIds(request.objectIds());
        if (request.linkTypeId() == null || request.linkTypeId() < 1) {
            throw BusinessException.invalidField("linkTypeId", "관계 유형 ID를 1 이상으로 지정해 주세요.");
        }
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        LinkType linkType =
                linkTypes
                        .findById(request.linkTypeId())
                        .filter(
                                type ->
                                        access.canView(type.getSourceTypeId())
                                                && access.canView(type.getTargetTypeId()))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "link_type",
                                                        "id",
                                                        request.linkTypeId())));
        boolean sameEnds = linkType.getSourceTypeId().equals(linkType.getTargetTypeId());
        if (request.direction() == Direction.BOTH && !sameEnds) {
            throw BusinessException.invalidField(
                    "direction", "출발·도착 유형이 다른 관계 유형은 OUT 또는 IN을 지정해 주세요.");
        }

        Map<Long, Long> baseTypes =
                objects.findActiveObjectTypes(baseIds, access.visibleObjectTypeIds());
        for (long id : baseIds) {
            if (!baseTypes.containsKey(id)) {
                throw notFound(id);
            }
        }
        Map<Long, Long> exampleByType = new LinkedHashMap<>();
        baseIds.forEach(id -> exampleByType.putIfAbsent(baseTypes.get(id), id));
        if (exampleByType.size() > 1) {
            throw mixedBaseTypes(linkType, exampleByType);
        }
        long firstId = baseIds.getFirst();
        long givenTypeId = baseTypes.get(firstId);
        Direction direction =
                request.direction() == null
                        ? inferDirection(linkType, sameEnds, firstId, givenTypeId)
                        : request.direction();
        long baseTypeId =
                direction == Direction.IN ? linkType.getTargetTypeId() : linkType.getSourceTypeId();
        long relatedTypeId =
                direction == Direction.IN ? linkType.getSourceTypeId() : linkType.getTargetTypeId();
        if (givenTypeId != baseTypeId) {
            throw baseTypeMismatch(linkType, direction, firstId);
        }

        Map<String, List<String>> conditions = new LinkedHashMap<>();
        request.propertyValues()
                .forEach((code, value) -> conditions.put(code, Collections.singletonList(value)));
        request.propertyValueSets()
                .forEach(
                        (code, values) -> {
                            if (conditions.putIfAbsent(code, values) != null) {
                                throw BusinessException.invalidField(
                                        "propertyValueSets[" + code + "]",
                                        "같은 속성을 propertyValues와 propertyValueSets에 함께 넣을 수 없습니다.");
                            }
                        });
        SearchFilters filters =
                filterValidator.validateAny(
                        relatedTypeId,
                        conditions,
                        request.dateProperty(),
                        request.from(),
                        request.to(),
                        access);
        if (!filters.values().isEmpty()) {
            filters = matchStoredSpacing(filters, relatedTypeId, Status.ACTIVE);
        }
        List<Long> related =
                objects.searchAround(
                        baseIds,
                        linkType.getId(),
                        direction,
                        relatedTypeId,
                        filters,
                        SEARCH_AROUND_LIMIT + 1);
        if (related.size() > SEARCH_AROUND_LIMIT) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "연결된 객체가 " + SEARCH_AROUND_LIMIT + "건을 넘습니다. 상대 객체의 속성이나 기간 조건을 더해 범위를 줄여 주세요.",
                    Map.of("limit", SEARCH_AROUND_LIMIT));
        }
        return ObjectSearchAroundResult.of(direction, relatedTypeId, related);
    }

    /**
     * 방향을 생략하면 기준 객체 유형이 관계 유형의 어느 끝인지로 정한다. AI가 방향을 잘못 고르거나 빠뜨려 다시 호출하는 일을 줄이기 위해서다. 양 끝 유형이 같으면
     * 어느 쪽인지 정할 수 없어 BOTH로 본다. 첫 기준 객체로 정하고, 다른 기준 객체가 맞지 않으면 호출자의 유형 검사가 거절한다.
     */
    private static Direction inferDirection(
            LinkType linkType, boolean sameEnds, long baseId, long baseTypeId) {
        if (sameEnds) {
            return Direction.BOTH;
        }
        if (baseTypeId == linkType.getSourceTypeId()) {
            return Direction.OUT;
        }
        if (baseTypeId == linkType.getTargetTypeId()) {
            return Direction.IN;
        }
        throw BusinessException.invalidField(
                "objectIds",
                "기준 객체 #"
                        + baseId
                        + "의 유형이 관계 유형의 어느 끝과도 맞지 않습니다. 관계 유형을 확인해 주세요. "
                        + linkEnds(linkType));
    }

    /**
     * Agent가 오류만 보고 무엇을 고칠지 알 수 있게 원인별로 문구를 나눈다. 기준 객체 유형이 섞였으면 방향·관계 유형이 아니라 그것이 원인이므로, 객체 순서나 방향
     * 지정과 관계없이 먼저 알린다. 유형별로 예시 객체를 하나씩 든다. 기준 객체와 관계 유형의 양 끝은 이미 볼 수 있다고 확인했으므로 유형 ID를 알려도 된다.
     */
    private static BusinessException mixedBaseTypes(
            LinkType linkType, Map<Long, Long> exampleByType) {
        List<String> examples = new ArrayList<>();
        exampleByType.forEach(
                (typeId, objectId) -> examples.add("유형 #" + typeId + ": 객체 #" + objectId));
        return BusinessException.invalidField(
                "objectIds",
                "기준 객체의 유형이 섞여 있습니다("
                        + String.join(", ", examples)
                        + "). 한 유형의 객체만 지정해 주세요. "
                        + linkEnds(linkType));
    }

    private static BusinessException baseTypeMismatch(
            LinkType linkType, Direction direction, long id) {
        String reason;
        if (direction == Direction.BOTH) {
            reason = "기준 객체 #" + id + "의 유형이 관계 유형의 끝 유형과 다릅니다. 관계 유형을 확인해 주세요.";
        } else {
            reason =
                    "기준 객체 #"
                            + id
                            + "의 유형이 관계 유형의 "
                            + (direction == Direction.IN ? "도착" : "출발")
                            + " 유형과 다릅니다. 방향을 생략하거나 관계 유형을 확인해 주세요.";
        }
        return BusinessException.invalidField("objectIds", reason + " " + linkEnds(linkType));
    }

    private static String linkEnds(LinkType linkType) {
        return "(관계 유형 #"
                + linkType.getId()
                + ": 출발 유형 #"
                + linkType.getSourceTypeId()
                + ", 도착 유형 #"
                + linkType.getTargetTypeId()
                + ")";
    }

    private static List<Long> baseObjectIds(List<Long> objectIds) {
        if (objectIds.isEmpty()) {
            throw BusinessException.invalidField("objectIds", "기준 객체 ID를 1개 이상 지정해 주세요.");
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long id : objectIds) {
            if (id == null || id < 1) {
                throw BusinessException.invalidField("objectIds", "객체 ID는 1 이상이어야 합니다.");
            }
            distinct.add(id);
        }
        if (distinct.size() > SEARCH_AROUND_LIMIT) {
            throw BusinessException.invalidField(
                    "objectIds", "기준 객체는 " + SEARCH_AROUND_LIMIT + "개까지 지정할 수 있습니다.");
        }
        return List.copyOf(distinct);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectLinkEvidenceResponse linkEvidence(LoginAccount actor, long objectId, long linkId) {
        return linkEvidence(actor, DataPurpose.VIEW, objectId, linkId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectLinkEvidenceResponse linkEvidence(
            LoginAccount actor, DataPurpose purpose, long objectId, long linkId) {
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (!visibleTypeIds.isEmpty()) {
            var evidence =
                    objects.findLinkEvidence(
                            objectId, linkId, visibleTypeIds, maskedTypes(access, visibleTypeIds));
            if (evidence.isPresent()) {
                return evidence.get();
            }
        }
        throw new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                Map.of("resource", "object_link", "id", linkId));
    }

    private static List<Long> maskedTypes(DataAccess access, Set<Long> visibleTypeIds) {
        return visibleTypeIds.stream().filter(id -> !access.canViewRestricted(id)).toList();
    }

    private static BusinessException notFound(long objectId) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                Map.of("resource", "object_instance", "id", objectId));
    }

    /** 응답 한 줄을 만들고, 가리지 않고 보여 준 민감 속성이 있으면 seen에 담는다(394 열람 기록). */
    private static ObjectSearchResponse toResponse(
            ObjectRow row, DataAccess access, List<Seen> seen) {
        MaskedProperties masked = access.mask(row.objectTypeId(), row.properties());
        seen.add(Seen.object(row.id(), masked.revealedPropertyCodes()));
        return new ObjectSearchResponse(
                row.id(),
                row.objectTypeId(),
                row.objectTypeName(),
                row.displayName(),
                masked.properties(),
                masked.restrictedPropertyCodes(),
                row.status(),
                row.sourceMappingId(),
                row.sourceKey(),
                row.firstCollectedAt(),
                row.lastSyncedAt(),
                dataAsOf(row.asOfCode(), masked.properties()));
    }

    private static Instant dataAsOf(String code, Map<String, Object> properties) {
        if (code == null || !(properties.get(code) instanceof String value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
