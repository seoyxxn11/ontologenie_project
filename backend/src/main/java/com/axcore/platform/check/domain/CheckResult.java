package com.axcore.platform.check.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
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

    // 문제 행의 원본값·변환값·판정 근거 등을 담은 JSONB 배열이다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_rows", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> sampleRows;
}
