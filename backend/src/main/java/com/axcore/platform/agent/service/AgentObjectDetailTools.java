package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;

/**
 * 객체 상세(077)와 활성 관계(078) 조회 도구(326). {@link ObjectDetailQuery} 구현(S15P31C106-137)이 있을 때만 AI에게 준다.
 * 권한·민감 속성 처리는 조회 서비스가 하고, 여기서는 질문한 사용자로 부르기만 한다. 기준 시점(테이블 확인 시각)은 결과에 나온 객체들의 원천 표 기준이다(145).
 */
public class AgentObjectDetailTools {
    static final String OBJECT_DETAIL = "OBJECT_DETAIL";
    static final String OBJECT_LINKS = "OBJECT_LINKS";

    private final AgentQuerySession session;
    private final ObjectDetailQuery objects;
    // 관계 조회 결과의 상대 객체 속성을 함께 읽는다(434).
    private final ObjectSearchQuery related;

    /**
     * AI에게 넘기는 객체 상세. 077 응답과 같고 시각만 한국 시간 오프셋으로 적는다. dataAsOf는 객체 값의 업무 기준일이라 UTC로 넘기면 날짜가 하루 틀어질
     * 수 있다(145). 077 API 응답은 바꾸지 않는다.
     */
    public record ObjectDetail(
            long id,
            long objectTypeId,
            String objectTypeName,
            String displayName,
            Map<String, Object> properties,
            Map<String, String> propertyNames,
            List<String> restrictedPropertyCodes,
            ObjectSearchRequest.Status status,
            Long sourceMappingId,
            String sourceSystemName,
            String sourceResourceKey,
            Map<String, Object> sourceKey,
            String firstCollectedAt,
            String lastSyncedAt,
            String dataAsOf) {

        static ObjectDetail from(ObjectDetailResponse response) {
            return new ObjectDetail(
                    response.id(),
                    response.objectTypeId(),
                    response.objectTypeName(),
                    response.displayName(),
                    response.properties(),
                    response.propertyNames(),
                    response.restrictedPropertyCodes(),
                    response.status(),
                    response.sourceMappingId(),
                    response.sourceSystemName(),
                    response.sourceResourceKey(),
                    response.sourceKey(),
                    AgentQuerySession.inUserZone(response.firstCollectedAt()),
                    AgentQuerySession.inUserZone(response.lastSyncedAt()),
                    AgentQuerySession.inUserZone(response.dataAsOf()));
        }
    }

    public AgentObjectDetailTools(
            AgentQuerySession session, ObjectDetailQuery objects, ObjectSearchQuery related) {
        this.session = session;
        this.objects = objects;
        this.related = related;
    }

    @Tool(
            name = OBJECT_DETAIL,
            description =
                    "객체 하나의 속성, 원천 위치, 수집 시각을 조회한다. 관계 조회의 relatedObjects나 목록 결과에 이미 속성이 온 객체는 속성"
                            + " 값만 필요하면 다시 조회하지 않는다. 원천 위치·수집 시각·속성 이름이 필요하면 부른다.")
    public Map<String, Object> objectDetail(@ToolParam(description = "객체 ID") Long objectId) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectId", objectId);
        return session.run(
                OBJECT_DETAIL,
                "객체 상세",
                input,
                AgentQueryTools.targetTypeOf(objectId),
                objectId,
                () ->
                        AgentQueryTools.noDataIfMissing(
                                () -> {
                                    long id = AgentQueryTools.required(objectId, "objectId");
                                    ObjectDetailResponse detail =
                                            objects.detail(session.asker(), DataPurpose.MODEL, id);
                                    return new AgentQuerySession.Outcome(
                                            ObjectDetail.from(detail),
                                            AgentResultCategory.FOUND,
                                            List.of(Map.of("objectId", id)),
                                            session.dataAsOfObjects(List.of(id)));
                                }));
    }

    /**
     * 객체는 있지만 볼 수 있는 활성 관계가 없으면 UNLINKED다(Action 정의서 8-1). 객체가 없거나 볼 수 없으면 NO_DATA다. 관계가 있는데 page가
     * 마지막 쪽을 넘으면 links만 비고 FOUND다. 관계 유형 없이 조회해 한 쪽에 다 담지 못하면 관계 유형·방향별 건수(groups)를 함께 준다(350).
     */
    @Tool(
            name = OBJECT_LINKS,
            description =
                    "객체에 연결된 확정 관계를 한 번에 최대 20건 조회한다. 연결 상대 객체의 ID·이름과 관계 근거를 돌려주고, 상대 객체의 속성은"
                            + " relatedObjects에 함께 온다. 관계 유형 없이 조회해 total이 20건을 넘으면 groups에 관계 유형·방향별 건수가 온다."
                            + " 그때는 찾는 정보에 맞는 묶음의 linkTypeId·direction으로 다시 조회하고, hasNext가 true면 page를 올려"
                            + " 다음 쪽을 본다.")
    public Map<String, Object> objectLinks(
            @ToolParam(description = "기준 객체 ID") Long objectId,
            @ToolParam(
                            description = "관계 유형 ID. 앞 관계 조회의 groups나 모델 요약에 나온 id로만 채운다. 지어내지 않는다",
                            required = false)
                    Long linkTypeId,
            @ToolParam(
                            description = "OUT(기준 객체에서 나가는 관계), IN(들어오는 관계), BOTH(기본, 양쪽)",
                            required = false)
                    String direction,
            @ToolParam(
                            description = "쪽 번호(0부터, 기본 0). 앞 결과의 hasNext가 true일 때 다음 쪽을 볼 때만 채운다",
                            required = false)
                    Integer page) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectId", objectId);
        AgentQueryTools.putIfPresent(input, "linkTypeId", linkTypeId);
        AgentQueryTools.putIfPresent(input, "direction", direction);
        AgentQueryTools.putIfPresent(input, "page", page);
        return session.run(
                OBJECT_LINKS,
                "관계",
                input,
                AgentQueryTools.targetTypeOf(objectId),
                objectId,
                () ->
                        AgentQueryTools.noDataIfMissing(
                                () -> {
                                    long id = AgentQueryTools.required(objectId, "objectId");
                                    int pageNumber = pageNumber(page);
                                    Page<ObjectLinkResponse> links =
                                            objects.links(
                                                    session.asker(),
                                                    DataPurpose.MODEL,
                                                    id,
                                                    new ObjectLinkSearchRequest(
                                                            linkTypeId,
                                                            AgentQueryTools.enumOf(
                                                                    ObjectLinkSearchRequest
                                                                            .Direction.class,
                                                                    direction),
                                                            pageNumber,
                                                            AgentQueryTools.MAX_ROWS));
                                    Map<String, Object> data = new LinkedHashMap<>();
                                    data.put("total", links.getTotalElements());
                                    data.put("page", pageNumber);
                                    data.put("hasNext", links.hasNext());
                                    // 데이터가 많으면 AI가 20건만 보고 멈추거나 실행 이력으로 대신 찾았다(342 GMS).
                                    // 어떤 관계가 몇 건인지 알려 줘 그 묶음으로 좁혀 다시 조회하게 한다.
                                    if (linkTypeId == null
                                            && links.getTotalElements()
                                                    > links.getNumberOfElements()) {
                                        data.put(
                                                "groups",
                                                objects.linkGroups(
                                                        session.asker(), DataPurpose.MODEL, id));
                                    }
                                    data.put("links", links.getContent());
                                    // 상대 객체의 속성을 함께 준다. 없으면 AI가 상대마다 상세를 다시 조회했다(434 측정에서 도구
                                    // 호출의 22%). 076과 같은 권한·가림(MODEL 용도)으로 읽고, 볼 수 없는 객체는 빠진다.
                                    List<AgentQueryTools.ObjectSummary> partners =
                                            relatedObjects(id, links.getContent());
                                    if (!partners.isEmpty()) {
                                        data.put("relatedObjects", partners);
                                    }
                                    // 연결이 없으면(UNLINKED) 기준 객체의 표만, 있으면 상대 객체의 표까지 본다.
                                    Set<Long> linked = objectIds(id, links.getContent());
                                    return new AgentQuerySession.Outcome(
                                            data,
                                            links.getTotalElements() == 0
                                                    ? AgentResultCategory.UNLINKED
                                                    : AgentResultCategory.FOUND,
                                            linked.stream()
                                                    .map(
                                                            found ->
                                                                    Map.<String, Object>of(
                                                                            "objectId", found))
                                                    .toList(),
                                            session.dataAsOfObjects(linked));
                                }));
    }

    /** 관계 상대 객체를 속성과 함께 읽는다. 기준 객체는 빼고, 같은 상대는 한 번만 담는다. */
    private List<AgentQueryTools.ObjectSummary> relatedObjects(
            long baseId, List<ObjectLinkResponse> links) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ObjectLinkResponse link : links) {
            if (link.relatedObject() != null && link.relatedObject().id() != baseId) {
                ids.add(link.relatedObject().id());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return related.findByIds(session.asker(), DataPurpose.MODEL, ids).stream()
                .map(AgentQueryTools.ObjectSummary::from)
                .toList();
    }

    /** AI가 음수 쪽 번호를 보내면 조회하지 않고 오류로 기록한다. */
    private static int pageNumber(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new IllegalArgumentException("page는 0 이상이어야 합니다.");
        }
        return page;
    }

    /** 기준 객체와 연결 상대 객체. 화면이 답변에서 각 객체를 열 수 있게 출처로 남기고, 기준 시점도 이 객체들의 표로 구한다. */
    private static Set<Long> objectIds(long objectId, List<ObjectLinkResponse> links) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(objectId);
        links.stream()
                .map(ObjectLinkResponse::relatedObject)
                .filter(Objects::nonNull)
                .forEach(related -> ids.add(related.id()));
        return ids;
    }
}
