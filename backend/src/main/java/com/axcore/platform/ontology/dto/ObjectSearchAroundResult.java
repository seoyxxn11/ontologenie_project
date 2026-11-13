package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import java.util.List;

/**
 * Search Around(379) 결과. 실제로 건넌 방향(생략했으면 추론한 방향), 상대 객체 유형 하나, 그 유형의 객체 ID를 중복 없이 id 순으로 담는다.
 * count는 objectIds의 건수다.
 */
public record ObjectSearchAroundResult(
        Direction direction, long objectTypeId, List<Long> objectIds, int count) {

    public static ObjectSearchAroundResult of(
            Direction direction, long objectTypeId, List<Long> objectIds) {
        List<Long> ids = List.copyOf(objectIds);
        return new ObjectSearchAroundResult(direction, objectTypeId, ids, ids.size());
    }
}
