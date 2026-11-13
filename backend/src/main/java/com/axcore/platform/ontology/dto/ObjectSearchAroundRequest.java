package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기준 객체 여러 개에서 한 관계 유형으로 한 홉을 건너는 조건(379). 078 공개 API에는 없고 Agent의 Object Set Tool이 쓴다. direction을
 * 생략하면 기준 객체 유형으로 정한다. 상대 객체 조건(propertyValues, dateProperty, from, to)은 076과 같다.
 */
public record ObjectSearchAroundRequest(
        List<Long> objectIds,
        Long linkTypeId,
        Direction direction,
        Map<String, String> propertyValues,
        String dateProperty,
        String from,
        String to) {

    public ObjectSearchAroundRequest {
        objectIds =
                objectIds == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(objectIds));
        propertyValues =
                propertyValues == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(propertyValues));
    }

    public ObjectSearchAroundRequest(List<Long> objectIds, Long linkTypeId, Direction direction) {
        this(objectIds, linkTypeId, direction, Map.of(), null, null, null);
    }
}
