package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.PropertyValueSampleService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;

/**
 * 조건으로 객체 목록과 전체 건수를 조회하는 도구(S15P31C106-359). "정지 중인 설비", "10월 작업지시"처럼 종류 전체를 조건으로 묻는 질문에 쓴다. 076을
 * 질문한 사용자로 불러 볼 수 없는 유형은 0건, 민감 속성은 값을 가리고, 볼 수 없는 민감 속성으로 거르면 DENIED다(205). AI가 조건에 쓸 속성 코드를 알 수
 * 있게 그 유형에서 볼 수 있는 속성 목록(043과 같은 조회 범위)과 문자 속성의 저장 값 종류를 함께 준다.
 */
public class AgentObjectListTools {
    static final String OBJECT_LIST = "OBJECT_LIST";

    /** 문자 속성의 값 종류를 이 수까지만 알려 준다. 더 많으면 상태 같은 범주 값이 아니라고 보고 알려 주지 않는다. */
    static final int MAX_VALUES = 20;

    /** 질문의 날짜는 사용자 기준 날짜다. 날짜만 받은 DATETIME 기간은 이 시간대의 하루로 바꾼다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final AgentQuerySession session;
    private final ObjectSearchQuery objects;
    private final ModelSummaryService models;
    private final DataAccessPolicy dataAccess;
    private final PropertyValueSampleService values;

    public AgentObjectListTools(
            AgentQuerySession session,
            ObjectSearchQuery objects,
            ModelSummaryService models,
            DataAccessPolicy dataAccess,
            PropertyValueSampleService values) {
        this.session = session;
        this.objects = objects;
        this.models = models;
        this.dataAccess = dataAccess;
        this.values = values;
    }

    /**
     * 조건에 쓸 수 있는 속성 하나. 볼 수 없는 민감 속성은 빠진다. values는 문자 속성에 저장된 값 종류이고, 문자 속성이 아니거나 종류가 20개를 넘으면
     * null이다.
     */
    public record PropertyItem(
            String code, String name, PropertyDataType dataType, List<String> values) {}

    /**
     * total은 조건에 맞는 전체 건수이고 objects는 그중 이번 쪽(최대 20건)이다. 076처럼 권한 범위 안에서만 센다. properties는 그 유형에서 조건에
     * 쓸 수 있는 속성이다. unmatchedValues는 저장 값 종류에서 맞는 값을 찾지 못한 속성 조건이다(속성 코드 → 넣은 값, 378).
     */
    public record ObjectList(
            long total,
            int page,
            boolean hasNext,
            List<AgentQueryTools.ObjectSummary> objects,
            List<PropertyItem> properties,
            Map<String, String> unmatchedValues) {}

    /**
     * API 076. 원천에서 삭제된 객체는 다루지 않는다(사용 중인 객체만). 잘못된 조건(없는 속성, 자료형에 맞지 않는 값, 거꾸로 된 기간)은 실패로 기록하고
     * AI에게 고칠 이유를 돌려준다. 기준 시점은 이번 쪽 객체들이 나온 원천 표 기준이다(145).
     */
    @Tool(
            name = OBJECT_LIST,
            description =
                    "객체 유형 하나에서 속성 값·기간 조건에 맞는 객체 목록(최대 20건)과 전체 건수(total)를 조회한다. \"정지 중인 설비\", \"10월"
                            + " 작업지시\"처럼 종류 전체를 조건으로 물을 때 쓴다. 유형 ID는 모델 요약의 id를 쓴다. 조건에 쓸 속성 코드는"
                            + " 결과의 properties에서, 값은 properties의 values(저장된 값 종류)에서 고른다. 모르면 조건 없이 먼저"
                            + " 조회한다. 값은 values에 있는 값 그대로 쓰고, 사용자의 말과 뜻이 가장 가까운 값을 고른다(예: \"진행 중\" →"
                            + " \"진행\"). 띄어쓰기만 다른 값은 서버가 저장된 값으로 맞춘다. unmatchedValues에 나온 조건은 저장된 값에 없는"
                            + " 값이니 values에서 다시 고르고, 맞는 값이 없으면 조건으로 거르지 않고 그런 값이 없다고 말한다. total이 objects"
                            + " 수보다 많으면 일부만 받은 것이니 전체 건수와 일부만 보여 준다는 것을 함께 말한다.")
    public Map<String, Object> objectList(
            @ToolParam(description = "객체 유형 ID(모델 요약의 id)") Long objectTypeId,
            @ToolParam(
                            description =
                                    "속성 값 조건. 속성 코드를 키로, 저장된 값을 값으로 둔다(예: {\"status\": \"가동\"}). 여러 개면 모두"
                                            + " 만족하는 객체만 찾는다. 없으면 비운다",
                            required = false)
                    Map<String, String> propertyValues,
            @ToolParam(
                            description = "기간으로 거를 날짜(DATE)·시각(DATETIME) 속성 코드. 기간이 없으면 비운다",
                            required = false)
                    String dateProperty,
            @ToolParam(description = "기간 시작일(yyyy-MM-dd, 이 날 포함)", required = false) String from,
            @ToolParam(description = "기간 종료일(yyyy-MM-dd, 이 날 포함)", required = false) String to,
            @ToolParam(
                            description = "쪽 번호(0부터, 기본 0). 앞 결과의 hasNext가 true일 때 다음 쪽을 볼 때만 채운다",
                            required = false)
                    Integer page) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectTypeId", objectTypeId);
        if (propertyValues != null && !propertyValues.isEmpty()) {
            input.put("propertyValues", propertyValues);
        }
        AgentQueryTools.putIfPresent(input, "dateProperty", dateProperty);
        AgentQueryTools.putIfPresent(input, "from", from);
        AgentQueryTools.putIfPresent(input, "to", to);
        AgentQueryTools.putIfPresent(input, "page", page);
        return session.run(
                OBJECT_LIST,
                "객체 목록",
                input,
                null,
                null,
                () -> {
                    if (objectTypeId == null) {
                        throw new AgentQuerySession.InvalidInput(
                                "objectTypeId가 없습니다. 모델 요약의 객체 유형 id를 넣어 주세요.", null);
                    }
                    long typeId = objectTypeId;
                    int pageNumber = pageNumber(page);
                    List<PropertyItem> properties = visibleProperties(typeId);
                    PropertyDataType periodType = dataTypeOf(properties, dateProperty);
                    Page<ObjectSearchResponse> found;
                    try {
                        found =
                                objects.search(
                                        session.asker(),
                                        new ObjectSearchRequest(
                                                null,
                                                typeId,
                                                ObjectSearchRequest.Status.ACTIVE,
                                                pageNumber,
                                                AgentQueryTools.MAX_ROWS,
                                                propertyValues,
                                                blankToNull(dateProperty),
                                                periodStart(periodType, from),
                                                periodEnd(periodType, to)));
                    } catch (BusinessException ex) {
                        if (ex.getErrorCode() == CommonErrorCode.FORBIDDEN) {
                            // 볼 수 없는 민감 속성으로 거르려 했다. 결과 건수로 값이 드러나지 않게 076이 거절한다.
                            return new AgentQuerySession.Outcome(
                                    List.of(), AgentResultCategory.DENIED, List.of(), null);
                        }
                        if (ex.getErrorCode() == CommonErrorCode.VALIDATION_FAILED) {
                            throw new AgentQuerySession.InvalidInput(reasonOf(ex), ex);
                        }
                        throw ex;
                    }
                    List<AgentQueryTools.ObjectSummary> rows =
                            found.getContent().stream()
                                    .map(AgentQueryTools.ObjectSummary::from)
                                    .toList();
                    List<Long> ids = rows.stream().map(AgentQueryTools.ObjectSummary::id).toList();
                    // 결과가 없으면 어느 표를 봤는지 객체로 알 수 없어 기준 시점을 비운다(정책 문서 4.2).
                    return new AgentQuerySession.Outcome(
                            new ObjectList(
                                    found.getTotalElements(),
                                    pageNumber,
                                    found.hasNext(),
                                    rows,
                                    properties,
                                    unmatchedValues(properties, propertyValues)),
                            found.getTotalElements() == 0
                                    ? AgentResultCategory.NO_DATA
                                    : AgentResultCategory.FOUND,
                            ids.stream().map(id -> Map.<String, Object>of("objectId", id)).toList(),
                            session.dataAsOfObjects(ids));
                });
    }

    /**
     * 043과 같은 범위다. 볼 수 없는 유형이면 빈 목록이고, 볼 수 없는 민감 속성은 빠진다. 값 종류는 ontology가 같은 조회 범위로 거른 문자 속성만 준다.
     */
    private List<PropertyItem> visibleProperties(long typeId) {
        return visibleProperties(session.asker(), typeId, models, dataAccess, values);
    }

    /** Object Set 도구(380)도 상대 유형의 속성을 같은 범위로 알려 줄 때 쓴다. */
    static List<PropertyItem> visibleProperties(
            LoginAccount asker,
            long typeId,
            ModelSummaryService models,
            DataAccessPolicy dataAccess,
            PropertyValueSampleService values) {
        Map<String, List<String>> samples = values.stringValues(asker, typeId, MAX_VALUES);
        return models.summarize(dataAccess.of(asker)).properties().stream()
                .filter(property -> Objects.equals(property.objectTypeId(), typeId))
                .map(
                        property ->
                                new PropertyItem(
                                        property.code(),
                                        property.name(),
                                        property.dataType(),
                                        samples.get(property.code())))
                .toList();
    }

    /**
     * 넣은 조건 값이 저장 값 종류에 없으면 알려 준다(378). 076은 똑같은 값이 없을 때 띄어쓰기만 다른 저장 값이 하나면 그 값으로 거르므로 같은 기준으로 본다.
     * 띄어쓰기만 다른 값이 여럿이거나 하나도 없으면 맞추지 못한 값이다. 값 종류를 모르는 속성(문자 속성이 아니거나 종류가 많음)은 판단하지 않는다.
     */
    static Map<String, String> unmatchedValues(
            List<PropertyItem> properties, Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        Map<String, String> unmatched = new LinkedHashMap<>();
        requested.forEach(
                (code, value) ->
                        properties.stream()
                                .filter(property -> property.code().equals(code))
                                .map(PropertyItem::values)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .filter(stored -> !matchesStored(stored, value))
                                .ifPresent(stored -> unmatched.put(code, value)));
        return unmatched;
    }

    private static boolean matchesStored(List<String> stored, String value) {
        if (value == null || stored.contains(value)) {
            return true;
        }
        String compact = withoutSpaces(value);
        return stored.stream().filter(candidate -> withoutSpaces(candidate).equals(compact)).count()
                == 1;
    }

    private static String withoutSpaces(String value) {
        return value.codePoints()
                .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    static PropertyDataType dataTypeOf(List<PropertyItem> properties, String code) {
        return properties.stream()
                .filter(property -> property.code().equals(code))
                .map(PropertyItem::dataType)
                .findFirst()
                .orElse(null);
    }

    /*
     * 076은 DATETIME 기간에 시간대가 있는 시각을 받는다. AI는 사용자 날짜(yyyy-MM-dd)로 보내므로 한국 시간 하루의 처음과 끝으로 바꾼다. 076의 끝은
     * 포함이라 끝 날의 마지막 마이크로초까지 넣는다. PostgreSQL 시각은 마이크로초까지라 나노초로 넣으면 다음 날 0시로 반올림된다. 날짜가 아니거나 DATE
     * 속성이면 그대로 넘겨 076이 검증한다.
     */

    static String periodStart(PropertyDataType type, String date) {
        LocalDate day = userDate(type, date);
        return day == null
                ? blankToNull(date)
                : day.atStartOfDay(USER_ZONE).toOffsetDateTime().toString();
    }

    static String periodEnd(PropertyDataType type, String date) {
        LocalDate day = userDate(type, date);
        return day == null
                ? blankToNull(date)
                : day.plusDays(1)
                        .atStartOfDay(USER_ZONE)
                        .minusNanos(1_000)
                        .toOffsetDateTime()
                        .toString();
    }

    private static LocalDate userDate(PropertyDataType type, String date) {
        if (type != PropertyDataType.DATETIME || date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String reasonOf(BusinessException ex) {
        Object field = ex.getDetails().get("field");
        Object reason = ex.getDetails().get("reason");
        return reason == null ? ex.getMessage() : field + ": " + reason;
    }

    /** AI가 음수 쪽 번호를 보내면 조회하지 않고 오류로 기록한다. */
    private static int pageNumber(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new AgentQuerySession.InvalidInput("page는 0 이상이어야 합니다.", null);
        }
        return page;
    }
}
