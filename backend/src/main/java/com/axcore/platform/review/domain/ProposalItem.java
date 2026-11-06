package com.axcore.platform.review.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import com.axcore.platform.global.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "proposal_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalItem extends BaseAuditEntity {

    public static ProposalItem manual(Map<String, Object> payload, Map<String, Object> evidence) {
        ProposalItem item = new ProposalItem();
        item.origin = "MANUAL";
        item.itemKind = "LINK_RULE";
        item.revision = 1;
        item.payload = payload;
        item.evidence = evidence;
        item.reviewStatus = "PENDING";
        item.applyStatus = "NOT_APPLIED";
        return item;
    }

    public void revise(Map<String, Object> payload, Map<String, Object> evidence) {
        if (!"PENDING".equals(reviewStatus) && !"HELD".equals(reviewStatus)) {
            throw new IllegalStateException("검토 중이거나 보류된 후보만 수정할 수 있습니다.");
        }
        revision++;
        this.payload = payload;
        this.evidence = evidence;
        aiConfidence = null;
        reviewStatus = "PENDING";
    }

    public void approve(Instant appliedAt) {
        requireReviewable();
        if (appliedAt == null) {
            throw new IllegalArgumentException("반영 시각은 필수입니다.");
        }
        reviewStatus = "APPROVED";
        applyStatus = "APPLIED";
        this.appliedAt = appliedAt;
        applyError = null;
    }

    public void reject() {
        requireReviewable();
        reviewStatus = "REJECTED";
        clearApplication();
    }

    public void hold() {
        requireReviewable();
        reviewStatus = "HELD";
        clearApplication();
    }

    private void requireReviewable() {
        if (!"PENDING".equals(reviewStatus) && !"HELD".equals(reviewStatus)) {
            throw new BusinessException(ReviewErrorCode.PROPOSAL_ALREADY_DECIDED);
        }
    }

    private void clearApplication() {
        applyStatus = "NOT_APPLIED";
        appliedAt = null;
        applyError = null;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;

    @Column(nullable = false, length = 20)
    private String origin;

    @Column(nullable = false, length = 30)
    private String itemKind;

    @Column(nullable = false)
    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    @Column(precision = 5, scale = 4)
    private BigDecimal aiConfidence;

    @Column(nullable = false, length = 20)
    private String reviewStatus;

    @Column(nullable = false, length = 20)
    private String applyStatus;

    @Column(columnDefinition = "text")
    private String applyError;

    private Instant appliedAt;

    @Version private int rowVersion;
}
