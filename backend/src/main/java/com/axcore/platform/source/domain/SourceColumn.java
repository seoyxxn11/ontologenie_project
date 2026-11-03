package com.axcore.platform.source.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원천 컬럼 목록 스냅샷(SYNC 계정 기준). 연결 검사(배치)가 채운다. 원천에서 사라져도 지우지 않고 isPresent=false로 남겨, 그 컬럼을 쓰는 매핑을 찾을 수
 * 있게 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourceResourceId;

    private String columnName;

    private String sourceDataType;

    private boolean isNullable;

    private boolean isKeyPart;

    private int ordinalPosition;

    @Column(columnDefinition = "text")
    private String sourceComment;

    private boolean isPresent;

    private Instant lastSeenAt;
}
