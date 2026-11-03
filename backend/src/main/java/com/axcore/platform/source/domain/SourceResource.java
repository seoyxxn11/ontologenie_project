package com.axcore.platform.source.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 원천 안의 표·뷰·문서 집합 (FN-SRC-02). 연결 검사(배치)가 채우고 api는 읽기만 한다. 매핑(object_source_mapping)과 수집 기록이 이 행을
 * 가리킨다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long dataSourceId;

    /** 원천 안에서의 이름. 표·뷰는 {스키마}.{이름}. */
    private String resourceKey;

    @Enumerated(EnumType.STRING)
    private ResourceKind resourceKind;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> sourceMetadata;

    /** SYNC 계정 기준 읽기 가능 여부. AI 계정 기준은 저장하지 않는다. */
    private boolean isReadable;

    @Enumerated(EnumType.STRING)
    private SyncStrategy syncStrategy;

    /** CDC 테이블을 마지막으로 전체 읽기 직전의 원천 LSN. NULL이면 아직 전체 읽기 전이라 이 테이블의 CDC 이벤트를 버린다. */
    private Long loadedLsn;

    private Instant lastSeenAt;
}
