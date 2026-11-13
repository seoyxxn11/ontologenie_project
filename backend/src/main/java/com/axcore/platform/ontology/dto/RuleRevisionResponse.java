package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API 명세의 RuleRevision(046·047). 연결 규칙 개정본(link_mapping) 하나와 그 비교 항목. 퇴역 시각·설명·생성자·원본 제안처럼 비어 있을 수
 * 있는 칸도 null로 내보낸다(명세 예시와 같음).
 *
 * <p>관계 유형·속성 이름은 개정 당시가 아니라 지금 이름이다(Jira 386). 화면이 번호 대신 이름을 보여 주는 데 쓴다. 개정본이 가리키는 ID는 그대로라 이력은
 * 바뀌지 않는다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuleRevisionResponse(
        long id,
        String code,
        int revision,
        long linkTypeId,
        String linkTypeName,
        String status,
        List<Condition> conditions,
        Map<String, Object> sourceFilter,
        Map<String, Object> targetFilter,
        Instant retiredAt,
        String description,
        Instant createdAt,
        Long createdBy,
        DefinitionOrigin origin,
        Long proposalItemId) {

    /**
     * @param linkTypeName 관계 유형의 지금 이름
     * @param conditions 이 개정본의 비교 항목. 비교 순서대로
     * @param propertyNames 속성 ID → 지금 이름. 비교 항목이 가리키는 속성을 모두 담는다
     */
    public static RuleRevisionResponse of(
            LinkMapping rule,
            String linkTypeName,
            List<LinkMappingCondition> conditions,
            Map<Long, String> propertyNames) {
        return new RuleRevisionResponse(
                rule.getId(),
                rule.getCode(),
                rule.getRevision(),
                rule.getLinkTypeId(),
                linkTypeName,
                rule.getStatus().name(),
                conditions.stream().map(c -> Condition.from(c, propertyNames)).toList(),
                rule.getSourceFilter(),
                rule.getTargetFilter(),
                rule.getRetiredAt(),
                rule.getDescription(),
                rule.getCreatedAt(),
                rule.getCreatedBy(),
                rule.getOrigin(),
                rule.getProposalItemId());
    }

    /** EQ 비교 항목 하나. 항목끼리는 AND다. 변환 단계가 없으면 빈 배열이다(041 형식). 속성 이름은 지금 이름이다. */
    public record Condition(
            int sequenceNo,
            long sourcePropertyId,
            String sourcePropertyName,
            long targetPropertyId,
            String targetPropertyName,
            String operator,
            List<Map<String, Object>> sourceTransforms,
            List<Map<String, Object>> targetTransforms) {

        static Condition from(LinkMappingCondition condition, Map<Long, String> propertyNames) {
            return new Condition(
                    condition.getSequenceNo(),
                    condition.getSourcePropertyId(),
                    propertyNames.get(condition.getSourcePropertyId()),
                    condition.getTargetPropertyId(),
                    propertyNames.get(condition.getTargetPropertyId()),
                    condition.getOperator(),
                    condition.getSourceTransforms() == null
                            ? List.of()
                            : condition.getSourceTransforms(),
                    condition.getTargetTransforms() == null
                            ? List.of()
                            : condition.getTargetTransforms());
        }
    }
}
