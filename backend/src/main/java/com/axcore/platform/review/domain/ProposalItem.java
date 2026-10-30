package com.axcore.platform.review.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
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
