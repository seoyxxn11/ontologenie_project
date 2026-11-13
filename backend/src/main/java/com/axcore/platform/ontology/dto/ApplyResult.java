package com.axcore.platform.ontology.dto;

import com.axcore.platform.audit.domain.ChangeEntry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 후보 하나를 반영한 결과. 모두 131의 트랜잭션 안에서 만들어졌고, 그 트랜잭션이 커밋돼야 남는다.
 *
 * @param targetTable 대표로 만든 행의 테이블(object_type, property_definition, link_type,
 *     object_source_mapping, property_source_mapping, link_mapping, object_link)
 * @param targetId 그 행의 ID
 * @param reloadResourceIds 매핑을 반영해 전체 다시 읽기를 요청한 원천 자원(source_resource). 객체는 수집 배치가 만든다
 * @param summary 감사 기록의 result에 넣을 요약. 예: 연결 유지·비활성·신규 수
 * @param changes 바뀐 행의 전후 내용. 감사 기록의 changes에 그대로 넣는다
 */
public record ApplyResult(
        String targetTable,
        long targetId,
        List<Long> reloadResourceIds,
        Map<String, Object> summary,
        List<ChangeEntry> changes) {

    public ApplyResult {
        Objects.requireNonNull(targetTable, "targetTable");
        if (targetId < 1) {
            throw new IllegalArgumentException("targetId는 1 이상입니다.");
        }
        reloadResourceIds = reloadResourceIds == null ? List.of() : List.copyOf(reloadResourceIds);
        summary =
                summary == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
