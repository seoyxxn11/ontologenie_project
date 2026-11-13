package com.axcore.platform.analysis.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 분석에 넘길 원천 범위를 원천 데이터 정책({@link SourceColumnPolicy})으로 정한다(S15P31C106-399). 018 접수와 AI 시작 알림이
 * 같이 쓴다.
 *
 * <ul>
 *   <li>금지 표는 분석할 수 없다(422).
 *   <li>금지·민감 컬럼은 AI에 넘기는 컬럼 목록에서 뺀다. AI는 받은 컬럼(과 기본키)만 읽고, 목록이 없거나 비어 있으면 표 전체를 읽는다.
 *   <li>그래서 정책 항목이 있는 표는 컬럼을 지정하지 않아도 카탈로그의 현재 컬럼에서 뺀 목록을 보낸다. 남는 컬럼이 없으면 422다.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AnalysisColumnScope {
    private final SourceColumnPolicy policy;

    /**
     * AI에 넘길 컬럼(이름순). {@code null}이면 표 전체다. 정책 항목이 없는 표에서 컬럼을 지정하지 않았을 때만 그렇다.
     *
     * @param requested 요청한 컬럼. 지정하지 않았으면 {@code null}
     * @param catalog 그 원천 자원의 현재 컬럼
     */
    public List<String> columnsForModel(
            String resourceKey, List<String> requested, Collection<String> catalog) {
        if (policy.forbidsTable(resourceKey)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천 데이터 정책상 들이지 않는 표라 분석할 수 없습니다.",
                    Map.of(
                            "field",
                            "targets",
                            "resourceKey",
                            resourceKey,
                            "forbiddenColumns",
                            List.of("*")));
        }
        if (requested == null && !policy.coversTable(resourceKey)) {
            return null;
        }
        List<String> allowed =
                new TreeSet<>(requested == null ? catalog : requested)
                        .stream()
                                .filter(
                                        column ->
                                                policy.gradeOf(resourceKey, column)
                                                        == SourceColumnGrade.NORMAL)
                                .toList();
        if (allowed.isEmpty()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천 데이터 정책상 분석할 수 있는 컬럼이 없습니다.",
                    Map.of(
                            "field",
                            "targets",
                            "resourceKey",
                            resourceKey,
                            "reason",
                            "NO_ANALYZABLE_COLUMNS"));
        }
        return allowed;
    }

    /**
     * AI에 넘길 등록 메타데이터. 컬럼별 설명({@code columns})에서 금지·민감 컬럼 항목을 뺀다. 표 설명·동의어·메모는 사람이 쓴 글이라 그대로 둔다.
     */
    public Map<String, Object> metadataForModel(String resourceKey, Map<String, Object> metadata) {
        if (!(metadata.get("columns") instanceof Map<?, ?> columns)) {
            return metadata;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        columns.forEach(
                (name, value) -> {
                    if (name instanceof String column
                            && policy.gradeOf(resourceKey, column) == SourceColumnGrade.NORMAL) {
                        kept.put(column, value);
                    }
                });
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.put("columns", kept);
        return copy;
    }
}
