package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.domain.LinkMappingRule;
import com.axcore.platform.check.domain.LinkMappingRule.ObjectTypeRef;
import com.axcore.platform.check.domain.ObjectRowFilter;
import com.axcore.platform.check.repository.CheckObjectReader;
import com.axcore.platform.check.repository.CheckObjectReader.CheckObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 연결 규칙 하나를 지금의 플랫폼 객체에 적용한다(388). 정합성 검사(191 {@link CheckRunJob})와 연결 재계산(181 {@code
 * LinkRecomputeService})이 같이 쓴다. 읽는 객체(연결 대상, {@code LinkableObjects}), 행 조건 해석({@link
 * ObjectRowFilter}), 판정({@link RuleEvaluationEngine})이 모두 여기 하나라, 검사가 정상(MATCHED)으로 판정한 쌍과 규칙 연결이
 * 같다.
 *
 * <p>부르는 쪽의 트랜잭션·스냅샷에서 읽는다. 검사는 REPEATABLE READ 읽기 스냅샷, 재계산은 관계 유형 잠금을 잡은 뒤의 READ COMMITTED다.
 */
@Component
@RequiredArgsConstructor
public class RuleObjectEvaluator {

    private final CheckObjectReader objects;
    private final RuleEvaluationEngine engine;

    /**
     * 규칙의 출발·도착 유형 객체를 읽어 판정한다. 객체 속성은 조건과 같이 {@code 유형코드.속성코드}로 넣는다.
     *
     * @throws RuleNotEvaluable 행 조건을 읽을 수 없을 때. 그 규칙만 건너뛴다
     */
    public Evaluated evaluate(LinkMappingRule rule) {
        List<CheckRow> sourceRows =
                rows(rule.sourceType(), rule.sourceResourceIds(), rule.sourceFilter(), "출발");
        List<CheckRow> targetRows =
                rows(rule.targetType(), rule.targetResourceIds(), rule.targetFilter(), "도착");
        CheckEvaluation evaluation =
                engine.evaluate(
                        new CheckInput(rule.input(), sourceRows, targetRows),
                        rule.sourceType().id() == rule.targetType().id());
        return new Evaluated(evaluation, targetRows);
    }

    private List<CheckRow> rows(
            ObjectTypeRef type, List<Long> resourceIds, Map<String, Object> stored, String side) {
        ObjectRowFilter filter =
                ObjectRowFilter.parse(stored, type.code())
                        .orElseThrow(() -> new RuleNotEvaluable(side + " 행 조건을 읽을 수 없습니다."));
        List<CheckRow> rows = new ArrayList<>();
        for (CheckObject object : objects.activeObjects(type.id(), resourceIds)) {
            if (!filter.matches(object.properties())) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            object.properties()
                    .forEach((code, value) -> values.put(type.code() + "." + code, value));
            rows.add(
                    new CheckRow(
                            object.sourceResourceId(), object.sourceKey(), object.id(), values));
        }
        return rows;
    }

    /**
     * @param evaluation 판정 결과
     * @param targetRows 판정에 넣은 도착 행(행 조건 적용 뒤). 검사가 수동 연결 대상을 찾는 데 쓴다
     */
    public record Evaluated(CheckEvaluation evaluation, List<CheckRow> targetRows) {}

    /** 규칙 하나를 판정할 수 없을 때(행 조건을 읽지 못함). 그 규칙만 건너뛴다. */
    public static final class RuleNotEvaluable extends RuntimeException {

        RuleNotEvaluable(String message) {
            super(message);
        }
    }
}
