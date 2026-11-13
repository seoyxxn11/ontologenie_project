package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.service.ModelSummaryService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 모델 요약(043) 조회 도구(S15P31C106-345). AI가 객체 검색·관계 조회를 객체 유형·관계 유형으로 좁힐 수 있게 그 ID와 이름을 준다. 질문한 사용자의
 * 조회 범위로 부르므로 볼 수 없는 유형과 그 유형에 걸린 관계 유형은 나오지 않는다(205). 속성·규칙 세부는 매 질문의 토큰만 늘려 넘기지 않는다.
 */
public class AgentModelSummaryTools {
    static final String MODEL_SUMMARY = "MODEL_SUMMARY";

    private final AgentQuerySession session;
    private final ModelSummaryService models;
    private final DataAccessPolicy dataAccess;

    public AgentModelSummaryTools(
            AgentQuerySession session, ModelSummaryService models, DataAccessPolicy dataAccess) {
        this.session = session;
        this.models = models;
        this.dataAccess = dataAccess;
    }

    /** 객체 유형 하나. id를 객체 검색의 객체 유형 ID로 쓴다. */
    public record ObjectTypeItem(Long id, String code, String name, String description) {

        static ObjectTypeItem from(ObjectTypeSummary type) {
            return new ObjectTypeItem(type.id(), type.code(), type.name(), type.description());
        }
    }

    /**
     * 관계 유형 하나. 출발(source) 유형에서 도착(target) 유형으로 가는 방향이다. id를 관계 조회의 관계 유형 ID로 쓴다. 유형 이름은 같은 결과의 객체
     * 유형에서 채운다.
     */
    public record LinkTypeItem(
            Long id,
            String code,
            String name,
            String description,
            Long sourceTypeId,
            String sourceTypeName,
            Long targetTypeId,
            String targetTypeName,
            LinkCardinality cardinality) {

        static LinkTypeItem from(LinkTypeSummary link, Map<Long, String> typeNames) {
            return new LinkTypeItem(
                    link.id(),
                    link.code(),
                    link.name(),
                    link.description(),
                    link.sourceTypeId(),
                    typeNames.get(link.sourceTypeId()),
                    link.targetTypeId(),
                    typeNames.get(link.targetTypeId()),
                    link.cardinality());
        }
    }

    public record ModelSummary(List<ObjectTypeItem> objectTypes, List<LinkTypeItem> linkTypes) {}

    /** 볼 수 있는 객체 유형이 없으면 NO_DATA다. 정의 정보라 원천 수집 시각이 없어 기준 시점은 비운다. */
    @Tool(
            name = MODEL_SUMMARY,
            description =
                    "볼 수 있는 객체 유형과 관계 유형의 ID·이름을 조회한다. 사용자가 유형 이름을 말해 객체 검색이나 관계 조회를 그 유형으로 좁혀야 할 때만 부른다.")
    public Map<String, Object> modelSummary() {
        return session.run(
                MODEL_SUMMARY,
                "모델 요약",
                Map.of(),
                null,
                null,
                () -> {
                    ModelSummaryResponse summary =
                            models.summarize(dataAccess.forModel(session.asker()));
                    Map<Long, String> typeNames =
                            summary.objectTypes().stream()
                                    .collect(
                                            Collectors.toMap(
                                                    ObjectTypeSummary::id,
                                                    ObjectTypeSummary::name,
                                                    (first, second) -> first));
                    ModelSummary data =
                            new ModelSummary(
                                    summary.objectTypes().stream()
                                            .map(ObjectTypeItem::from)
                                            .toList(),
                                    summary.linkTypes().stream()
                                            .map(link -> LinkTypeItem.from(link, typeNames))
                                            .toList());
                    return new AgentQuerySession.Outcome(
                            data,
                            data.objectTypes().isEmpty()
                                    ? AgentResultCategory.NO_DATA
                                    : AgentResultCategory.FOUND,
                            List.of(),
                            null);
                });
    }
}
