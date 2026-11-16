package com.axcore.platform.review.service;

import com.axcore.platform.review.dto.AiProposalRequest;
import com.axcore.platform.source.policy.SourceColumnGrade;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 105로 받은 AI 후보에 원천 데이터 정책({@link SourceColumnPolicy})을 적용한다(S15P31C106-399). 저장하기 전에 한 번 거친다. 018이
 * 금지·민감 컬럼을 AI에 넘기지 않으므로 이 단계는 두 번째 방어다.
 *
 * <ul>
 *   <li>근거의 원천 위치({@code locator}, {@code 스키마.표.컬럼}·{@code 스키마.표.a+b})가 금지 표나 금지·민감 컬럼을 가리키면 그 근거의
 *       예시 값({@code values})과 요약({@code snippet})을 지운다. 위치는 남긴다. 근거는 어느 깊이에 있어도(묶음 구성 근거, 선택지 근거)
 *       찾는다.
 *   <li>payload가 금지 표·컬럼을 매핑하면({@code resourceKey}와 {@code keyColumns}·{@code column}) 근거에 {@code
 *       sourcePolicy.forbiddenColumns}를 달고 보류 권고로 표시한다. 승인 반영은 391 정책으로 거절되므로 검토 화면에서 먼저 알 수 있게 한다.
 *       묶음 후보(OBJECT_TYPE)는 해당 구성 근거({@code evidence.objectMapping}, {@code
 *       evidence.properties[]})에도 표시한다.
 * </ul>
 *
 * <p>제안 이유 같은 설명 칸은 보통 고치지 않는다. 018이 금지·민감 컬럼의 값을 AI에 넘기지 않으므로 그 컬럼의 근거는 값 없이 온다. 값 없이 구조만 보고 만든
 * 후보의 설명까지 지우면 검토자가 볼 것이 남지 않는다. 다만 금지·민감 위치의 근거에 예시 값이 실려 왔으면 AI가 읽지 말아야 할 값을 읽은 것이다. 그때는 같은 값이 설명
 * 문장에도 들어 있을 수 있으므로, 그 근거를 품은 맵의 설명 칸을 모두 고정 문구로 바꿔 저장한다(384 보완). 문장을 바꿔 쓴 값과 짧은 값까지 덮으려고 문자열을 견주지
 * 않고 모두 지운다. 지운 위치는 경고 로그로 남기고 값은 남기지 않는다.
 *
 * <p>한계: 값이 {@code values} 없이 요약({@code snippet})에만 실려 온 경우는 요약은 지우지만 설명 칸은 그대로 둔다. AI는 값이 없는 컬럼에도
 * 건수 요약을 보내서, 요약이 있다는 것만으로는 값이 실려 왔는지 가를 수 없다. 제목과 payload(정의 후보)도 고치지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProposalSourcePolicy {
    static final String WITHHELD_SNIPPET = "원천 데이터 정책상 예시 값을 저장하지 않습니다.";

    static final String WITHHELD_TEXT = "원천 데이터 정책상 예시 값이 든 설명을 저장하지 않습니다.";

    private final SourceColumnPolicy policy;

    public AiProposalRequest apply(AiProposalRequest proposal) {
        List<AiProposalRequest.Option> options =
                proposal.options().stream()
                        .map(
                                option ->
                                        new AiProposalRequest.Option(
                                                option.optionNo(),
                                                option.payload(),
                                                evidence(option.payload(), option.evidence()),
                                                option.aiConfidence()))
                        .toList();
        return new AiProposalRequest(
                proposal.itemKind(),
                proposal.payload(),
                evidence(proposal.payload(), proposal.evidence()),
                proposal.aiConfidence(),
                options);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evidence(
            Map<String, Object> payload, Map<String, Object> evidence) {
        Set<String> carried = new TreeSet<>();
        Map<String, Object> copy = (Map<String, Object>) withheld(evidence, carried).value();
        if (!carried.isEmpty()) {
            // AI에 넘기지 않는 위치의 값이 돌아왔다. 값은 남기지 않고 위치만 알린다.
            log.warn("AI 제안 근거에 원천 데이터 정책상 받지 않는 위치의 예시 값이 실려 와 값과 설명을 지웠습니다: {}", carried);
        }
        List<String> forbidden = forbiddenMapped(payload);
        if (forbidden.isEmpty()) {
            return copy;
        }
        mark(copy, forbidden);
        if (payload.get("objectMapping") instanceof Map<?, ?> mapping
                && copy.get("objectMapping") instanceof Map<?, ?> part) {
            markIfForbidden(mapping, (Map<String, Object>) part);
        }
        if (payload.get("properties") instanceof List<?> properties
                && copy.get("properties") instanceof List<?> parts) {
            for (Object property : properties) {
                if (!(property instanceof Map<?, ?> item)) {
                    continue;
                }
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> found
                            && item.get("code") != null
                            && item.get("code").equals(found.get("code"))) {
                        markIfForbidden(item, (Map<String, Object>) found);
                    }
                }
            }
        }
        return copy;
    }

    private void markIfForbidden(Map<?, ?> payload, Map<String, Object> evidence) {
        List<String> forbidden = forbiddenMapped(payload);
        if (!forbidden.isEmpty()) {
            mark(evidence, forbidden);
        }
    }

    private static void mark(Map<String, Object> evidence, List<String> forbidden) {
        String reason =
                "원천 데이터 정책상 들이지 않는 컬럼을 매핑합니다("
                        + String.join(", ", forbidden)
                        + "). 승인해도 반영이 거절됩니다.";
        if (evidence.get("holdReason") instanceof String previous && !previous.isBlank()) {
            reason = reason + " / " + previous;
        }
        evidence.put("sourcePolicy", Map.of("forbiddenColumns", forbidden));
        evidence.put("holdRecommended", true);
        evidence.put("holdReason", reason);
    }

    /** 지운 뒤의 값과, 그 안에 예시 값이 실려 온 금지·민감 위치의 근거가 있었는지. */
    private record Cleaned(Object value, boolean carried) {}

    /**
     * 근거 트리를 복사하면서 금지·민감 위치를 가리키는 근거의 값을 지운다. 그 위치에 예시 값({@code values})이 실려 왔으면, 그 근거를 품은 맵(자신과 그
     * 위)의 설명 칸도 고정 문구로 바꾼다. 실려 온 위치는 carried에 담는다.
     */
    private Cleaned withheld(Object value, Set<String> carried) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            boolean found = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Cleaned child = withheld(entry.getValue(), carried);
                copy.put(String.valueOf(entry.getKey()), child.value());
                found |= child.carried();
            }
            if (copy.get("locator") instanceof String locator && governed(locator)) {
                if (copy.get("values") instanceof List<?> values && !values.isEmpty()) {
                    found = true;
                    carried.add(locator);
                }
                if (copy.containsKey("values")) {
                    copy.put("values", List.of());
                }
                if (copy.containsKey("snippet")) {
                    copy.put("snippet", WITHHELD_SNIPPET);
                }
            }
            if (found) {
                ProposalEvidenceScope.withholdText(copy, WITHHELD_TEXT);
            }
            return new Cleaned(copy, found);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            boolean found = false;
            for (Object item : list) {
                Cleaned child = withheld(item, carried);
                copy.add(child.value());
                found |= child.carried();
            }
            return new Cleaned(copy, found);
        }
        return new Cleaned(value, false);
    }

    /**
     * 위치가 금지 표이거나 금지·민감 컬럼(묶은 컬럼 {@code a+b}는 하나라도)을 가리키는가. 컬럼 이름에 마침표가 있을 수 있어 표 뒤는 더 쪼개지 않는다. 저장된
     * 근거를 읽을 때도 같은 판단을 쓴다(397, {@link ProposalEvidenceScope}).
     */
    boolean governed(String locator) {
        String[] parts = locator.split("\\.", 3);
        if (parts.length < 2) {
            return false;
        }
        String resourceKey = parts[0] + "." + parts[1];
        if (policy.forbidsTable(resourceKey)) {
            return true;
        }
        if (parts.length < 3) {
            return false;
        }
        if (policy.gradeOf(resourceKey, parts[2]) != SourceColumnGrade.NORMAL) {
            return true;
        }
        for (String column : parts[2].split("\\+")) {
            if (policy.gradeOf(resourceKey, column) != SourceColumnGrade.NORMAL) {
                return true;
            }
        }
        return false;
    }

    /** payload 안에서 금지 표·컬럼을 가리키는 매핑({@code 스키마.표.컬럼}, 표 전체는 {@code 스키마.표.*}). */
    private List<String> forbiddenMapped(Object payload) {
        Set<String> found = new LinkedHashSet<>();
        collectForbidden(payload, found);
        return List.copyOf(found);
    }

    private void collectForbidden(Object value, Set<String> found) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("resourceKey") instanceof String resourceKey) {
                if (policy.forbidsTable(resourceKey)) {
                    found.add(resourceKey + ".*");
                }
                List<Object> columns = new ArrayList<>();
                if (map.get("keyColumns") instanceof List<?> keys) {
                    columns.addAll(keys);
                }
                columns.add(map.get("column"));
                for (Object column : columns) {
                    if (column instanceof String name
                            && !policy.forbidsTable(resourceKey)
                            && policy.gradeOf(resourceKey, name) == SourceColumnGrade.FORBIDDEN) {
                        found.add(resourceKey + "." + name);
                    }
                }
            }
            map.values().forEach(child -> collectForbidden(child, found));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectForbidden(child, found));
        }
    }
}
