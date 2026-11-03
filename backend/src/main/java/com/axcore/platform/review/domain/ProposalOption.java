package com.axcore.platform.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Immutable
@Table(name = "proposal_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalOption {

    @Id private Long id;

    @Column(nullable = false)
    private Long proposalItemId;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private int optionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    @Column(precision = 5, scale = 4)
    private BigDecimal aiConfidence;
}
