package com.axcore.platform.agent.service;

import com.axcore.platform.agent.service.AgentObjectListTools.PropertyItem;
import com.axcore.platform.agent.service.AgentQuerySession.InvalidInput;
import com.axcore.platform.agent.service.AgentQuerySession.ObjectSet;
import com.axcore.platform.agent.service.AgentQuerySession.Outcome;
import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.dto.ObjectSearchAroundRequest;
import com.axcore.platform.ontology.dto.ObjectSearchAroundResult;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery.IdSearch;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Object Set 도구(S15P31C106-380). 여러 단계 떨어진 객체를 찾을 때 객체 목록은 서버가 들고, AI는 Object Set ID로 지시만 한다. 그래서
 * 단계가 늘어도 단계마다 도구를 한 번만 부른다. Object Set은 답변 하나 동안 {@link AgentQuerySession}에 둔다.
 *
 * <ul>
 *   <li>시작·건너기 결과에는 ID·건수·이름 몇 개만 준다. 객체 ID는 목록 보기에서만 주고, 목록 보기로 받은 객체만 허용 객체가 된다(326).
 *   <li>건너기는 379 Search Around 서비스를 질문한 사용자로 불러 078·205 권한을 그대로 따른다. 한 답변에서 {@value
 *       #MAX_SEARCH_AROUNDS}번까지 건넌다.
 *   <li>조회마다 AGENT_QUERY 기록과 기준 시점(145)을 남긴다.
 * </ul>
 */
public class AgentObjectSetTools {
    static final String OBJECT_SET_START = "OBJECT_SET_START";
    static final String OBJECT_SET_SEARCH_AROUND = "OBJECT_SET_SEARCH_AROUND";
    static final String OBJECT_SET_LIST = "OBJECT_SET_LIST";

    /** 한 답변에서 건널 수 있는 단계 수. 단계가 많으면 경로를 잘못 골랐을 가능성이 크다. */
    static final int MAX_SEARCH_AROUNDS = 5;

    /** 객체 ID로 시작할 때 받는 수. AI가 이미 아는 객체로 시작하는 경우라 많지 않다. */
    static final int MAX_START_OBJECTS = 20;

    /** Object Set 크기 상한. Search Around 서비스(379)와 같다. */
    static final int MAX_SET_SIZE = 5000;

    /** 시작·건너기 결과에 보여 주는 객체 이름 수. */
    static final int SAMPLE_SIZE = 3;

    private final AgentQuerySession session;
    private final ObjectSearchQuery objects;
    private final ObjectDetailQuery details;
    private final ModelSummaryService models;
    private final DataAccessPolicy dataAccess;
    private final PropertyValueSampleService values;

    public AgentObjectSetTools(
            AgentQuerySession session,
            ObjectSearchQuery objects,
            ObjectDetailQuery details,
            ModelSummaryService models,
            DataAccessPolicy dataAccess,
            PropertyValueSampleService values) {
        this.session = session;
        this.objects = objects;
        this.details = details;
        this.models = models;
        this.dataAccess = dataAccess;
        this.values = values;
    }

    /**
     * 객체 ID로 시작하면 그 객체들이 이번 답변의 대상이어야 한다(147). 조건으로 시작하면 076과 같은 조건·권한으로 찾는다. 조건에 맞는 객체가 {@value
     * #MAX_SET_SIZE}건을 넘으면 조건을 더하라고 돌려준다.
     */
    @Tool(
            name = OBJECT_SET_START,
            description =
                    "여러 단계 떨어진 정보를 찾을 때 출발점이 될 객체 집합(Object Set)을 서버에 만든다. objectIds(참고 객체) 또는 objectTypeId와 조건(OBJECT_LIST와 같은 조건) 중 하나로 만든다. 결과의 objectSetId로"
                            + " OBJECT_SET_SEARCH_AROUND를 불러 관계를 따라가고, 마지막에 OBJECT_SET_LIST로 객체를 본다. 결과에는 건수와"
                            + " 이름 몇 개, 이 유형에서 갈 수 있는 관계 유형(linkTypes: linkTypeId·direction·도착 유형)이 온다. 그사이"
                            + " 삭제되거나 볼 수 없게 된 시작 객체는 빼고 그 수를 unavailableObjects로 알린다.")
    public Map<String, Object> objectSetStart(
            @ToolParam(
                            description =
                                    "시작 객체 ID 목록(최대 20개, 같은 유형). '참고 객체 ID'를 쓴다. 관계 조회로 받은 일부 연결 상대로 시작하지 않는다. 조건으로"
                                            + " 시작하면 비운다",
                            required = false)
                    List<Long> objectIds,
            @ToolParam(
                            description = "조건으로 시작할 때의 객체 유형 ID(모델 요약의 id). objectIds로 시작하면 비운다",
                            required = false)
                    Long objectTypeId,
            @ToolParam(
                            description = "속성 값 조건(OBJECT_LIST와 같음). 속성 코드를 키로, 저장된 값을 값으로 둔다",
                            required = false)
                    Map<String, String> propertyValues,
            @ToolParam(description = "기간으로 거를 날짜·시각 속성 코드", required = false) String dateProperty,
            @ToolParam(description = "기간 시작일(yyyy-MM-dd, 이 날 포함)", required = false) String from,
            @ToolParam(description = "기간 종료일(yyyy-MM-dd, 이 날 포함)", required = false) String to) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectIds", objectIds);
        AgentQueryTools.putIfPresent(input, "objectTypeId", objectTypeId);
        putConditions(input, propertyValues, dateProperty, from, to);
        boolean byObjects = objectIds != null && !objectIds.isEmpty();
        if (byObjects
                && (objectIds.stream().anyMatch(Objects::isNull)
                        || !session.objectsAllowed(Set.copyOf(objectIds)))) {
            return Map.of("error", AgentQuerySession.NOT_TARGET);
        }
        return session.run(
                OBJECT_SET_START,
                "객체 집합 시작",
                input,
                null,
                null,
                () -> {
                    if (byObjects == (objectTypeId != null)) {
                        throw new InvalidInput(
                                "objectIds와 objectTypeId 중 하나만 넣어 주세요. 아는 객체로 시작하면 objectIds, 조건으로 시작하면"
                                        + " objectTypeId를 씁니다.",
                                null);
                    }
                    return byObjects
                            ? startFromObjects(objectIds)
                            : startFromConditions(
                                    objectTypeId, propertyValues, dateProperty, from, to);
                });
    }

    /**
     * 379 Search Around로 한 단계를 건너 새 Object Set을 만든다. 방향을 비우면 서버가 기준 객체 유형으로 정한다. 결과가 {@value
     * #MAX_SET_SIZE}건을 넘거나 조건이 잘못되면 고칠 이유를 돌려준다.
     */
    @Tool(
            name = OBJECT_SET_SEARCH_AROUND,
            description =
                    "Object Set의 객체들에서 관계 유형 하나를 따라 연결된 객체를 중복 없이 모아 새 Object Set을 만든다. 한 단계를 한 번에"
                            + " 건넌다. 연결된 객체의 속성·기간 조건을 함께 걸 수 있다(OBJECT_LIST와 같은 조건). 방향은 비우면 서버가 정한다."
                            + " 결과에는 새 objectSetId, 건수, 이름 몇 개, 연결된 유형의 속성(properties)과 그 유형에서 다음으로 갈 수 있는"
                            + " 관계 유형(linkTypes)이 온다. 출발 객체 중 그사이"
                            + " 삭제되거나 볼 수 없게 된 객체는 빼고 건너며 그 수를 unavailableBaseObjects로 알린다. 결과가 5000건을 넘으면"
                            + " 조건을 더해 다시 부른다. 한 질문에서 5번까지 건널 수 있다(실패한 호출은 세지 않는다).")
    public Map<String, Object> objectSetSearchAround(
            @ToolParam(description = "출발 Object Set ID(이번 답변에서 받은 objectSetId)") String objectSetId,
            @ToolParam(description = "따라갈 관계 유형 ID(객체 집합 결과의 linkTypes·경로 후보·모델 요약에 나온 id)")
                    Long linkTypeId,
            @ToolParam(
                            description =
                                    "OUT(관계의 출발 → 도착), IN(도착 → 출발), BOTH(양 끝 유형이 같은 관계만). 비우면 서버가 정한다",
                            required = false)
                    String direction,
            @ToolParam(description = "연결된 객체의 속성 값 조건. 속성 코드를 키로, 저장된 값을 값으로 둔다", required = false)
                    Map<String, String> propertyValues,
            @ToolParam(description = "연결된 객체를 기간으로 거를 날짜·시각 속성 코드", required = false)
                    String dateProperty,
            @ToolParam(description = "기간 시작일(yyyy-MM-dd, 이 날 포함)", required = false) String from,
            @ToolParam(description = "기간 종료일(yyyy-MM-dd, 이 날 포함)", required = false) String to) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectSetId", objectSetId);
        AgentQueryTools.putIfPresent(input, "linkTypeId", linkTypeId);
        AgentQueryTools.putIfPresent(input, "direction", direction);
        putConditions(input, propertyValues, dateProperty, from, to);
        return session.run(
                OBJECT_SET_SEARCH_AROUND,
                "관계 따라가기",
                input,
                null,
                null,
                () ->
                        searchAround(
                                objectSetId,
                                linkTypeId,
                                direction,
                                propertyValues,
                                dateProperty,
                                from,
                                to));
    }

    /** Object Set의 객체를 id 순으로 20건씩 속성과 함께 보여 준다. 여기서 받은 객체만 다른 도구로 더 조회할 수 있다. */
    @Tool(
            name = OBJECT_SET_LIST,
            description =
                    "Object Set의 객체를 한 번에 최대 20건 속성과 함께 보여 준다. total이 Object Set을 만들 때의 건수이고, hasNext가"
                            + " true면 page를 올려 다음 쪽을 본다. 그사이 삭제되거나 볼 수 없게 된 객체는 빠지고 그 수를"
                            + " unavailableOnPage로 알린다. 여기서 받은 객체만 OBJECT_DETAIL 같은 객체별 조회에 쓸 수 있다.")
    public Map<String, Object> objectSetList(
            @ToolParam(description = "Object Set ID(이번 답변에서 받은 objectSetId)") String objectSetId,
            @ToolParam(
                            description = "쪽 번호(0부터, 기본 0). 앞 결과의 hasNext가 true일 때 다음 쪽을 볼 때만 채운다",
                            required = false)
                    Integer page) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectSetId", objectSetId);
        AgentQueryTools.putIfPresent(input, "page", page);
        return session.run(
                OBJECT_SET_LIST,
                "객체 집합 목록",
                input,
                null,
                null,
                () -> {
                    ObjectSet set = requireSet(objectSetId);
                    int pageNumber = pageNumber(page);
                    int size = set.objectIds().size();
                    int start = (int) Math.min((long) pageNumber * AgentQueryTools.MAX_ROWS, size);
                    int end = Math.min(start + AgentQueryTools.MAX_ROWS, size);
                    List<Long> slice = set.objectIds().subList(start, end);
                    List<AgentQueryTools.ObjectSummary> rows =
                            slice.isEmpty()
                                    ? List.of()
                                    : objects
                                            .findByIds(session.asker(), DataPurpose.MODEL, slice)
                                            .stream()
                                            .map(AgentQueryTools.ObjectSummary::from)
                                            .toList();
                    List<Long> ids = rows.stream().map(AgentQueryTools.ObjectSummary::id).toList();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("objectSetId", set.id());
                    data.put("objectTypeId", set.objectTypeId());
                    data.put("total", size);
                    data.put("page", pageNumber);
                    data.put("hasNext", end < size);
                    data.put("objects", rows);
                    if (rows.size() < slice.size()) {
                        data.put("unavailableOnPage", slice.size() - rows.size());
                    }
                    return new Outcome(
                            data,
                            // 이 쪽의 객체가 모두 사라졌으면 보여 준 것이 없어 NO_DATA다. 마지막 쪽을 넘은 page는
                            // OBJECT_LIST·OBJECT_LINKS처럼 FOUND로 두고 빈 목록만 준다.
                            rows.isEmpty() && !slice.isEmpty()
                                    ? AgentResultCategory.NO_DATA
                                    : AgentResultCategory.FOUND,
                            ids.stream().map(id -> Map.<String, Object>of("objectId", id)).toList(),
                            session.dataAsOfObjects(ids));
                });
    }

    /**
     * 그사이 삭제되거나 볼 수 없게 된 시작 객체는 빼고 만들고 그 수를 unavailableObjects로 알린다. 건너기와 같은 원칙이다. 모두 사라졌으면
     * NO_DATA다.
     */
    private Outcome startFromObjects(List<Long> objectIds) {
        Set<Long> requested = new LinkedHashSet<>(objectIds);
        if (requested.size() > MAX_START_OBJECTS) {
            throw new InvalidInput("objectIds는 " + MAX_START_OBJECTS + "개까지 넣을 수 있습니다.", null);
        }
        List<ObjectSearchResponse> found =
                objects.findByIds(session.asker(), DataPurpose.MODEL, requested);
        int unavailable = requested.size() - found.size();
        if (found.isEmpty()) {
            Map<String, Object> gone = new LinkedHashMap<>();
            gone.put("count", 0);
            gone.put("unavailableObjects", unavailable);
            return new Outcome(gone, AgentResultCategory.NO_DATA, List.of(), null);
        }
        Set<Long> types =
                found.stream()
                        .map(ObjectSearchResponse::objectTypeId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (types.size() > 1) {
            throw new InvalidInput("한 유형의 객체만 넣어 주세요. 넣은 객체의 유형: " + types, null);
        }
        List<Long> ids = found.stream().map(ObjectSearchResponse::id).toList();
        ObjectSearchResponse first = found.getFirst();
        ObjectSet set = session.saveObjectSet(first.objectTypeId(), ids);
        Map<String, Object> data = setSummary(set, first.objectTypeName(), names(found));
        data.put("linkTypes", nextLinkTypes(summary(), set.objectTypeId()));
        if (unavailable > 0) {
            data.put("unavailableObjects", unavailable);
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        // 시작 객체는 이미 이번 답변의 대상이라 출처로 남겨도 허용 객체가 늘지 않는다.
        ids.forEach(id -> sources.add(Map.of("objectId", id)));
        return new Outcome(data, AgentResultCategory.FOUND, sources, session.dataAsOfObjects(ids));
    }

    private Outcome startFromConditions(
            long typeId,
            Map<String, String> propertyValues,
            String dateProperty,
            String from,
            String to) {
        List<PropertyItem> properties = properties(typeId);
        PropertyDataType periodType = AgentObjectListTools.dataTypeOf(properties, dateProperty);
        IdSearch found;
        try {
            // 건수와 ID만 같은 스냅샷에서 읽는다. 상한을 넘으면 ID는 읽지 않는다.
            found =
                    objects.searchIds(
                            session.asker(),
                            DataPurpose.MODEL,
                            new ObjectSearchRequest(
                                    null,
                                    typeId,
                                    ObjectSearchRequest.Status.ACTIVE,
                                    null,
                                    null,
                                    propertyValues,
                                    AgentObjectListTools.blankToNull(dateProperty),
                                    AgentObjectListTools.periodStart(periodType, from),
                                    AgentObjectListTools.periodEnd(periodType, to)),
                            MAX_SET_SIZE);
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CommonErrorCode.FORBIDDEN) {
                return denied();
            }
            if (ex.getErrorCode() == CommonErrorCode.VALIDATION_FAILED) {
                throw new InvalidInput(AgentObjectListTools.reasonOf(ex), ex);
            }
            throw ex;
        }
        if (found.total() > MAX_SET_SIZE) {
            throw new InvalidInput(
                    "조건에 맞는 객체가 "
                            + found.total()
                            + "건이라 "
                            + MAX_SET_SIZE
                            + "건을 넘습니다. 속성이나 기간 조건을 더해 주세요.",
                    null);
        }
        Map<String, String> unmatched =
                AgentObjectListTools.unmatchedValues(properties, propertyValues);
        if (found.ids().isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", 0);
            data.put("properties", properties);
            data.put("unmatchedValues", unmatched);
            return new Outcome(data, AgentResultCategory.NO_DATA, List.of(), null);
        }
        ObjectSet set = session.saveObjectSet(typeId, found.ids());
        List<ObjectSearchResponse> sample = sample(found.ids());
        Map<String, Object> data =
                setSummary(
                        set,
                        sample.isEmpty() ? null : sample.getFirst().objectTypeName(),
                        names(sample));
        data.put("properties", properties);
        data.put("unmatchedValues", unmatched);
        data.put("linkTypes", nextLinkTypes(summary(), typeId));
        return new Outcome(
                data,
                AgentResultCategory.FOUND,
                List.of(setSource(set)),
                session.dataAsOfObjects(found.ids()));
    }

    private Outcome searchAround(
            String objectSetId,
            Long linkTypeId,
            String direction,
            Map<String, String> propertyValues,
            String dateProperty,
            String from,
            String to) {
        ObjectSet set = requireSet(objectSetId);
        if (session.searchArounds() >= MAX_SEARCH_AROUNDS) {
            throw new InvalidInput(
                    "한 질문에서 관계 따라가기는 "
                            + MAX_SEARCH_AROUNDS
                            + "번까지입니다. 지금까지 만든 Object Set으로 답해 주세요.",
                    null);
        }
        if (linkTypeId == null) {
            throw new InvalidInput(
                    "linkTypeId가 없습니다. 객체 집합 결과의 linkTypes나 경로 후보에 나온 관계 유형 id를 넣어 주세요.", null);
        }
        Direction requested = directionOf(direction);
        ModelSummaryResponse summary = models.summarize(dataAccess.forModel(session.asker()));
        // 날짜만 받은 DATETIME 기간을 바꾸려면 연결된 유형의 속성 자료형을 미리 알아야 한다. 모르면 그대로 넘겨 서비스가 검증한다.
        Long expectedType = relatedTypeOf(summary, linkTypeId, set.objectTypeId(), requested);
        List<PropertyItem> expectedProperties =
                expectedType == null ? List.of() : properties(expectedType);
        PropertyDataType periodType =
                AgentObjectListTools.dataTypeOf(expectedProperties, dateProperty);
        // 답변 중에 삭제되거나 볼 수 없게 된 출발 객체가 있으면 379가 전체를 거절한다. 남은 객체로 건너고 빠진 수를 알린다.
        List<Long> available = objects.findAvailableIds(session.asker(), set.objectIds());
        int unavailable = set.objectIds().size() - available.size();
        if (available.isEmpty()) {
            Map<String, Object> gone = new LinkedHashMap<>();
            gone.put("fromObjectSetId", set.id());
            gone.put("count", 0);
            gone.put("unavailableBaseObjects", unavailable);
            Map<String, Object> goneSource = new LinkedHashMap<>();
            goneSource.put("fromObjectSetId", set.id());
            goneSource.put("linkTypeId", linkTypeId);
            goneSource.put("count", 0);
            goneSource.put("unavailableBaseObjects", unavailable);
            return new Outcome(gone, AgentResultCategory.NO_DATA, List.of(goneSource), null);
        }
        ObjectSearchAroundResult result;
        try {
            result =
                    details.searchAround(
                            session.asker(),
                            DataPurpose.MODEL,
                            new ObjectSearchAroundRequest(
                                    available,
                                    linkTypeId,
                                    requested,
                                    propertyValues,
                                    AgentObjectListTools.blankToNull(dateProperty),
                                    AgentObjectListTools.periodStart(periodType, from),
                                    AgentObjectListTools.periodEnd(periodType, to)));
        } catch (BusinessException ex) {
            return searchAroundFailure(ex, linkTypeId);
        }
        // 결과에 넣을 값을 모두 읽은 뒤에 Object Set을 저장하고 횟수를 센다. 뒤 조회가 실패해 답이 실패가 되면 세지 않는다.
        long relatedType = result.objectTypeId();
        List<PropertyItem> properties =
                Objects.equals(expectedType, relatedType)
                        ? expectedProperties
                        : properties(relatedType);
        List<String> sample = result.count() == 0 ? List.of() : names(sample(result.objectIds()));
        String relatedTypeName = typeName(summary, relatedType);
        // 379에 실제로 넘긴 조건으로 판단한다. 빈 문자열만 보낸 조건은 조건이 아니다.
        boolean filtered =
                (propertyValues != null && !propertyValues.isEmpty())
                        || AgentObjectListTools.blankToNull(dateProperty) != null
                        || AgentObjectListTools.blankToNull(from) != null
                        || AgentObjectListTools.blankToNull(to) != null;
        AgentResultCategory category =
                result.count() > 0
                        ? AgentResultCategory.FOUND
                        : filtered ? AgentResultCategory.NO_DATA : AgentResultCategory.UNLINKED;
        // 기준 시점(145): 연결이 없으면(UNLINKED) 출발 객체의 표, 있으면 연결된 객체의 표까지 본다(OBJECT_LINKS와 같다).
        // 조건을 걸어 0건(NO_DATA)이면 조건을 건 상대 유형의 표를 객체로 알 수 없어 비운다(정책 문서 4.2).
        Set<Long> seen = new LinkedHashSet<>(available);
        seen.addAll(result.objectIds());
        Instant asOf =
                category == AgentResultCategory.NO_DATA ? null : session.dataAsOfObjects(seen);

        ObjectSet next =
                result.count() > 0 ? session.saveObjectSet(relatedType, result.objectIds()) : null;
        session.countSearchAround();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectSetId", next == null ? null : next.id());
        data.put("fromObjectSetId", set.id());
        data.put("linkTypeId", linkTypeId);
        data.put("direction", result.direction());
        data.put("objectTypeId", relatedType);
        data.put("objectTypeName", relatedTypeName);
        data.put("count", result.count());
        data.put("sample", sample);
        data.put("properties", properties);
        data.put("linkTypes", nextLinkTypes(summary, relatedType));
        data.put(
                "unmatchedValues",
                AgentObjectListTools.unmatchedValues(properties, propertyValues));
        data.put("remainingSearchArounds", MAX_SEARCH_AROUNDS - session.searchArounds());
        if (unavailable > 0) {
            data.put("unavailableBaseObjects", unavailable);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("objectSetId", next == null ? null : next.id());
        source.put("fromObjectSetId", set.id());
        source.put("linkTypeId", linkTypeId);
        source.put("direction", result.direction().name());
        source.put("objectTypeId", relatedType);
        source.put("count", result.count());
        if (unavailable > 0) {
            source.put("unavailableBaseObjects", unavailable);
        }
        return new Outcome(data, category, List.of(source), asOf);
    }

    /** 고칠 수 있는 입력 오류는 이유를 돌려주고, 볼 수 없는 민감 속성 조건은 DENIED로 답한다. */
    private Outcome searchAroundFailure(BusinessException ex, long linkTypeId) {
        CommonErrorCode code = ex.getErrorCode() instanceof CommonErrorCode common ? common : null;
        if (code == CommonErrorCode.FORBIDDEN) {
            return denied();
        }
        if (code == CommonErrorCode.VALIDATION_FAILED) {
            throw new InvalidInput(AgentObjectListTools.reasonOf(ex), ex);
        }
        if (code == CommonErrorCode.BUSINESS_RULE_VIOLATION) {
            throw new InvalidInput(ex.getMessage(), ex);
        }
        if (code == CommonErrorCode.RESOURCE_NOT_FOUND
                && "link_type".equals(ex.getDetails().get("resource"))) {
            throw new InvalidInput(
                    "관계 유형 #"
                            + linkTypeId
                            + "를 찾을 수 없습니다. 객체 집합 결과의 linkTypes나 경로 후보에 나온 관계 유형 id를 넣어 주세요.",
                    ex);
        }
        // 출발 객체를 확인한 뒤 건너기 전에 또 사라진 경우다. "데이터 없음"이 아니라 확인하지 못한 것으로 답하게 실패로 둔다.
        throw ex;
    }

    /**
     * 연결된 객체의 유형을 미리 짐작한다. 379 서비스와 같은 규칙이다. 관계 유형이 모델 요약에 없거나(볼 수 없음) 기준 유형이 어느 끝과도 맞지 않으면 null이다.
     */
    static Long relatedTypeOf(
            ModelSummaryResponse summary, long linkTypeId, long baseTypeId, Direction direction) {
        LinkTypeSummary linkType =
                summary.linkTypes().stream()
                        .filter(candidate -> Objects.equals(candidate.id(), linkTypeId))
                        .findFirst()
                        .orElse(null);
        if (linkType == null) {
            return null;
        }
        if (direction == Direction.OUT) {
            return linkType.targetTypeId();
        }
        if (direction == Direction.IN) {
            return linkType.sourceTypeId();
        }
        if (Objects.equals(linkType.sourceTypeId(), linkType.targetTypeId())
                || Objects.equals(linkType.sourceTypeId(), baseTypeId)) {
            return linkType.targetTypeId();
        }
        return Objects.equals(linkType.targetTypeId(), baseTypeId) ? linkType.sourceTypeId() : null;
    }

    private ObjectSet requireSet(String objectSetId) {
        ObjectSet set = session.objectSet(objectSetId);
        if (set == null) {
            throw new InvalidInput(
                    "objectSetId '"
                            + objectSetId
                            + "'를 찾을 수 없습니다. 이번 답변에서 OBJECT_SET_START나 OBJECT_SET_SEARCH_AROUND가 돌려준"
                            + " objectSetId만 쓸 수 있습니다.",
                    null);
        }
        return set;
    }

    /**
     * 그 유형에서 다음 단계로 갈 수 있는 관계 유형(382). 모델 요약과 같은 조회 범위라 볼 수 없는 유형으로 가는 관계는 빠진다. AI가 다음 건너기의
     * linkTypeId·direction을 모델 요약을 따로 부르지 않고 고를 수 있게 한다.
     */
    public record NextLinkType(
            long linkTypeId,
            String name,
            String direction,
            long objectTypeId,
            String objectTypeName) {}

    static List<NextLinkType> nextLinkTypes(ModelSummaryResponse summary, long typeId) {
        List<NextLinkType> next = new ArrayList<>();
        for (LinkTypeSummary link : summary.linkTypes()) {
            boolean fromHere = Objects.equals(link.sourceTypeId(), typeId);
            boolean toHere = Objects.equals(link.targetTypeId(), typeId);
            if (fromHere && toHere) {
                next.add(
                        new NextLinkType(
                                link.id(), link.name(), "BOTH", typeId, typeName(summary, typeId)));
            } else if (fromHere) {
                next.add(
                        new NextLinkType(
                                link.id(),
                                link.name(),
                                "OUT",
                                link.targetTypeId(),
                                typeName(summary, link.targetTypeId())));
            } else if (toHere) {
                next.add(
                        new NextLinkType(
                                link.id(),
                                link.name(),
                                "IN",
                                link.sourceTypeId(),
                                typeName(summary, link.sourceTypeId())));
            }
        }
        return next;
    }

    private ModelSummaryResponse summary() {
        return models.summarize(dataAccess.of(session.asker()));
    }

    private List<PropertyItem> properties(long typeId) {
        return AgentObjectListTools.visibleProperties(
                session.asker(), typeId, models, dataAccess, values);
    }

    private static Direction directionOf(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        try {
            return Direction.valueOf(direction.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidInput("direction은 OUT, IN, BOTH 중 하나이거나 비워야 합니다.", ex);
        }
    }

    private static int pageNumber(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new InvalidInput("page는 0 이상이어야 합니다.", null);
        }
        return page;
    }

    private static Map<String, Object> setSummary(
            ObjectSet set, String typeName, List<String> sample) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectSetId", set.id());
        data.put("objectTypeId", set.objectTypeId());
        data.put("objectTypeName", typeName);
        data.put("count", set.objectIds().size());
        data.put("sample", sample);
        return data;
    }

    /** Object Set 단계의 출처. 객체 ID는 넣지 않아 허용 객체가 늘지 않는다(326). */
    private static Map<String, Object> setSource(ObjectSet set) {
        return Map.of(
                "objectSetId", set.id(),
                "objectTypeId", set.objectTypeId(),
                "count", set.objectIds().size());
    }

    /** 이름 샘플로 보여 줄 앞 객체 몇 개. */
    private List<ObjectSearchResponse> sample(List<Long> ids) {
        return objects.findByIds(
                session.asker(),
                DataPurpose.MODEL,
                ids.subList(0, Math.min(SAMPLE_SIZE, ids.size())));
    }

    /** 객체 이름 몇 개. ID는 주지 않는다. 객체별 조회는 목록 보기로 받은 객체만 하게 하기 위해서다. */
    private static List<String> names(List<ObjectSearchResponse> found) {
        return found.stream().limit(SAMPLE_SIZE).map(ObjectSearchResponse::displayName).toList();
    }

    private static String typeName(ModelSummaryResponse summary, long typeId) {
        return summary.objectTypes().stream()
                .filter(type -> Objects.equals(type.id(), typeId))
                .map(ObjectTypeSummary::name)
                .findFirst()
                .orElse(null);
    }

    private static Outcome denied() {
        return new Outcome(List.of(), AgentResultCategory.DENIED, List.of(), null);
    }

    private static void putConditions(
            Map<String, Object> input,
            Map<String, String> propertyValues,
            String dateProperty,
            String from,
            String to) {
        if (propertyValues != null && !propertyValues.isEmpty()) {
            input.put("propertyValues", propertyValues);
        }
        AgentQueryTools.putIfPresent(input, "dateProperty", dateProperty);
        AgentQueryTools.putIfPresent(input, "from", from);
        AgentQueryTools.putIfPresent(input, "to", to);
    }
}
