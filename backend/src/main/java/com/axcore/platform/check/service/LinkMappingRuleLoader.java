package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckRuleInput;
import com.axcore.platform.check.domain.CheckTarget;
import com.axcore.platform.check.domain.LinkMappingRule;
import com.axcore.platform.check.domain.LinkMappingRule.ObjectTypeRef;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.check.repository.LinkMappingRuleRepository;
import com.axcore.platform.check.repository.LinkMappingRuleRepository.ConditionRow;
import com.axcore.platform.check.repository.LinkMappingRuleRepository.RuleRow;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인된 연결 규칙 개정본을 판정 엔진의 공통 입력({@link CheckRuleInput})으로 바꾼다(S15P31C106-190, FN-RUL-02).
 *
 * <p>규칙 개정본 ID 자체가 검사 대상이다. 개정본은 바뀌지 않으므로 같은 ID와 같은 데이터 기준이면 같은 입력이 나온다. 행
 * 조건(source_filter·target_filter)은 판정 엔진이 아니라 행을 읽는 쪽이 적용하므로 {@link LinkMappingRule}에 따로 담는다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LinkMappingRuleLoader {

    private static final TypeReference<List<Map<String, Object>>> STEPS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> FILTER = new TypeReference<>() {};

    private final LinkMappingRuleRepository repository;
    private final ObjectSourceMappingRepository mappingRepository;
    private final JsonMapper json;

    /** 이 규칙이 읽는 원천 자원(출발·도착 유형에 활성 매핑된 자원). 검사 요청 때 데이터 기준을 정하는 데 쓴다. */
    public List<Long> resourceIds(long linkMappingId) {
        RuleRow rule = requireRule(linkMappingId);
        TreeSet<Long> ids =
                new TreeSet<>(mappingRepository.findActiveSourceResourceIds(rule.sourceTypeId()));
        ids.addAll(mappingRepository.findActiveSourceResourceIds(rule.targetTypeId()));
        return List.copyOf(ids);
    }

    /**
     * 규칙 하나를 검사 입력으로 바꾼다.
     *
     * @param runBasis 검사 실행 전체의 데이터 기준. 이 규칙이 읽는 자원만 남겨 입력에 넣는다
     * @throws IllegalArgumentException 규칙이 없거나, 비교 조건이 없거나, 데이터 기준에 규칙이 읽는 자원이 빠졌을 때
     * @throws IllegalStateException 저장된 비교 조건·변환·행 조건을 읽을 수 없을 때
     */
    public LinkMappingRule load(long linkMappingId, CheckDataBasis runBasis) {
        RuleRow rule = requireRule(linkMappingId);
        List<Long> sourceIds = mappingRepository.findActiveSourceResourceIds(rule.sourceTypeId());
        List<Long> targetIds = mappingRepository.findActiveSourceResourceIds(rule.targetTypeId());

        TreeSet<Long> needed = new TreeSet<>(sourceIds);
        needed.addAll(targetIds);
        List<ResourceCheckpoint> checkpoints =
                runBasis.resources().stream()
                        .filter(checkpoint -> needed.contains(checkpoint.sourceResourceId()))
                        .toList();
        if (checkpoints.size() != needed.size()) {
            throw new IllegalArgumentException(
                    "검사 데이터 기준에 규칙 " + linkMappingId + "이 읽는 원천 자원이 빠졌습니다.");
        }

        List<CheckCondition> conditions = new ArrayList<>();
        for (ConditionRow row : repository.findConditions(linkMappingId)) {
            conditions.add(
                    new CheckCondition(
                            row.sequenceNo(),
                            row.sourceProperty(),
                            row.targetProperty(),
                            row.operator(),
                            steps(linkMappingId, row.sequenceNo(), row.sourceTransforms()),
                            steps(linkMappingId, row.sequenceNo(), row.targetTransforms())));
        }
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("규칙 " + linkMappingId + "에 비교 조건이 없습니다.");
        }

        CheckRuleInput input =
                new CheckRuleInput(
                        CheckTarget.forLinkMapping(linkMappingId),
                        new CheckDataBasis(checkpoints),
                        LinkCardinality.valueOf(rule.cardinality()),
                        conditions);
        return new LinkMappingRule(
                input,
                new ObjectTypeRef(rule.sourceTypeId(), rule.sourceTypeCode()),
                new ObjectTypeRef(rule.targetTypeId(), rule.targetTypeCode()),
                sourceIds,
                targetIds,
                filter(linkMappingId, rule.sourceFilter()),
                filter(linkMappingId, rule.targetFilter()));
    }

    /**
     * 지금의 객체에 적용할 규칙 입력(388). 연결 재계산이 쓴다. 검사 실행이 아니라 수집 실행 기준이 없으므로, 데이터 기준은 이 규칙이 읽는 원천 자원마다 수집 실행
     * 없이(null) 둔다. 그 밖의 변환은 {@link #load}와 같다.
     *
     * @throws IllegalArgumentException 규칙이 없거나 비교 조건이 없을 때
     * @throws IllegalStateException 저장된 비교 조건·변환·행 조건을 읽을 수 없을 때
     */
    public LinkMappingRule loadCurrent(long linkMappingId) {
        return load(
                linkMappingId,
                new CheckDataBasis(
                        resourceIds(linkMappingId).stream()
                                .map(id -> new ResourceCheckpoint(id, null))
                                .toList()));
    }

    private RuleRow requireRule(long linkMappingId) {
        return repository
                .findRule(linkMappingId)
                .orElseThrow(() -> new IllegalArgumentException("연결 규칙이 없습니다: " + linkMappingId));
    }

    /** 저장 형식 {@code [{"op": 함수, "arg": 인자}]}(037~039와 같다)을 변환 단계로 바꾼다. */
    private List<TransformStep> steps(long linkMappingId, int sequenceNo, String stored) {
        try {
            List<TransformStep> steps = new ArrayList<>();
            for (Map<String, Object> step : json.readValue(stored, STEPS)) {
                steps.add(
                        new TransformStep(
                                TransformOperation.valueOf(String.valueOf(step.get("op"))),
                                step.get("arg")));
            }
            return steps;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "규칙 "
                            + linkMappingId
                            + "의 조건 "
                            + sequenceNo
                            + " 변환을 읽을 수 없습니다: "
                            + ex.getMessage(),
                    ex);
        }
    }

    private Map<String, Object> filter(long linkMappingId, String stored) {
        try {
            return json.readValue(stored, FILTER);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("규칙 " + linkMappingId + "의 행 조건을 읽을 수 없습니다.", ex);
        }
    }
}
