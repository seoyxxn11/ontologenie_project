package com.axcore.platform.issue.domain;

import com.axcore.platform.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 정합성 문제 (FN-ISS-01~03). 행은 검사가 {@code IssueWriteRepository}로 만든다. 열린 문제 중복을 부분 유일 인덱스
 * (uq_issue_open)와 {@code on conflict ... do nothing}으로 막아야 해서 JPA로 저장하지 않는다. 이 Entity는 조회와 상태 변경에
 * 쓴다.
 *
 * <p>검사가 찾은 내용(종류·키·대상·값·근거)은 등록한 뒤 바꾸지 않는다.
 */
@Getter
@Entity
@Table(name = "issue")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue extends BaseTimeEntity {

    private static final String PASSED = "PASSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private IssueKind issueKind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IssueStatus status;

    @Column(nullable = false, updatable = false, length = 300)
    private String dedupeKey;

    @Column(updatable = false)
    private Long linkMappingId;

    @Column(updatable = false)
    private Long proposalItemId;

    @Column(updatable = false)
    private Long objectInstanceId;

    @Column(updatable = false, columnDefinition = "text")
    private String rawValue;

    @Column(updatable = false, columnDefinition = "text")
    private String transformedValue;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    @Column(nullable = false, updatable = false)
    private Long detectedCheckRunId;

    private Long resolvedCheckRunId;

    /** 해결이면 PASSED. 이 문제의 재검증 기록(issue_verification)과 복합 FK로 묶인다. */
    @Column(length = 10)
    private String resolvedOutcome;

    private Instant forwardedAt;

    private Long forwardedBy;

    @Version private int rowVersion;

    /** 해결이 아닌 다음 상태로 바꾼다. 해결은 {@link #resolve(long)}로 한다. */
    public void moveTo(IssueStatus next) {
        if (next == IssueStatus.RESOLVED) {
            throw new IllegalArgumentException("해결은 재검증 검사와 함께 resolve로 바꿉니다.");
        }
        requireNext(next);
        status = next;
        resolvedCheckRunId = null;
        resolvedOutcome = null;
    }

    /** 이 문제가 PASSED인 재검증을 근거로 해결한다. 근거가 쓸 수 있는 재검증인지는 호출하는 쪽이 먼저 확인한다. */
    public void resolve(long checkRunId) {
        requireNext(IssueStatus.RESOLVED);
        status = IssueStatus.RESOLVED;
        resolvedCheckRunId = checkRunId;
        resolvedOutcome = PASSED;
    }

    /**
     * 원천 담당자에게 플랫폼 밖에서 알렸다고 기록한다(API 068, FN-ISS-04). 다시 전달하면 마지막 전달로 덮어쓴다. 상태는 바꾸지 않는다. 해결된 문제는
     * 호출하는 쪽이 먼저 거절한다.
     */
    public void forward(long accountId, Instant at) {
        if (status == IssueStatus.RESOLVED) {
            throw new IllegalStateException("해결된 문제는 전달 기록을 남기지 않습니다.");
        }
        forwardedAt = at;
        forwardedBy = accountId;
    }

    private void requireNext(IssueStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("문제 상태를 " + status + "에서 " + next + "(으)로 바꿀 수 없습니다.");
        }
    }
}
