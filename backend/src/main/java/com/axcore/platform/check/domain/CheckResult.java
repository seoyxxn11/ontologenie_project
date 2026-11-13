package com.axcore.platform.check.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 규칙 하나 또는 후보 revision 하나에 대한 검사 집계와 근거 표본. */
@Getter
@Entity
@Table(name = "check_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_run_id", nullable = false)
    private Long checkRunId;

    // 검사 대상은 승인된 규칙 또는 후보 중 하나다. 두 ID 중 정확히 하나만 존재한다.
    @Column(name = "link_mapping_id")
    private Long linkMappingId;

    @Column(name = "proposal_item_id")
    private Long proposalItemId;

    // 현재 후보의 revision이 아니라 실제로 검사한 revision을 보존한다.
    @Column(name = "proposal_item_revision")
    private Integer proposalItemRevision;

    @Column(name = "previous_result_id")
    private Long previousResultId;

    // 각 원천 행은 한 분류에만 속하며, 네 분류의 건수 합은 totalCount와 같다.
    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "unmatched_count", nullable = false)
    private int unmatchedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "violation_count", nullable = false)
    private int violationCount;

    // 비교값으로 바꾸지 못해 대응 후보에서 뺀 도착 행 수(388). 출발 행 분류와 따로다.
    @Column(name = "excluded_target_count", nullable = false)
    private int excludedTargetCount;

    // 문제 행의 원본값·변환값·판정 근거 등을 담은 JSONB 배열이다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_rows", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> sampleRows;

    /**
     * 판정 엔진 결과 하나를 저장할 검사 결과로 만든다. 검사 실행기(191, 승인 규칙)와 후보 시험 실행기(CANDIDATE)가 같이 쓴다.
     *
     * <p>대상(승인 규칙 개정본 또는 후보 revision)과 건수는 판정 결과에서 그대로 가져온다. 그래서 "대상은 둘 중 하나"와 "네 분류의 합 = 전체"
     * ({@code ck_check_result_target}·{@code ck_check_result_counts})가 코드에서도 늘 지켜진다.
     *
     * @param checkRunId 이 결과를 낸 검사 실행
     * @param previousResultId 같은 대상의 직전 결과(재검증 비교용). 없으면 null
     * @param evaluation 판정 엔진 결과
     * @param sampleRows 문제 행 표본. 062 응답과 같은 형식({@code rawValue}·{@code transformedValue}·{@code
     *     classification}·{@code reason} 등). 값에 null을 넣을 수 있다
     */
    public static CheckResult of(
            long checkRunId,
            Long previousResultId,
            CheckEvaluation evaluation,
            List<Map<String, Object>> sampleRows) {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(sampleRows, "sampleRows");
        if (checkRunId < 1) {
            throw new IllegalArgumentException("검사 실행 ID는 1 이상이어야 합니다.");
        }
        if (previousResultId != null && previousResultId < 1) {
            throw new IllegalArgumentException("직전 결과 ID는 1 이상이어야 합니다.");
        }
        CheckTarget target = evaluation.rule().target();
        CheckResult result = new CheckResult();
        result.checkRunId = checkRunId;
        result.linkMappingId = target.linkMappingId();
        result.proposalItemId = target.proposalItemId();
        result.proposalItemRevision = target.proposalItemRevision();
        result.previousResultId = previousResultId;
        result.totalCount = evaluation.totalCount();
        result.matchedCount = evaluation.matchedCount();
        result.unmatchedCount = evaluation.unmatchedCount();
        result.duplicateCount = evaluation.duplicateCount();
        result.violationCount = evaluation.violationCount();
        result.excludedTargetCount = evaluation.excludedTargetCount();
        // 표본 값에 null이 있을 수 있어 Map.copyOf 대신 순서를 지키는 복사본을 쓴다
        List<Map<String, Object>> samples = new ArrayList<>(sampleRows.size());
        for (Map<String, Object> sample : sampleRows) {
            samples.add(Collections.unmodifiableMap(new LinkedHashMap<>(sample)));
        }
        result.sampleRows = Collections.unmodifiableList(samples);
        return result;
    }
}
