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
 * propertyValueSets는 속성마다 값을 여럿 받아 그중 하나와 같으면 된다(Agent 목표 유형 찾기). 같은 속성을 두 칸에 함께 넣을 수 없다.
 */
public record ObjectSearchAroundRequest(
        List<Long> objectIds,
        Long linkTypeId,
        Direction direction,
        Map<String, String> propertyValues,
        String dateProperty,
        String from,
        String to,
        Map<String, List<String>> propertyValueSets) {

    public ObjectSearchAroundRequest {
        objectIds =
                objectIds == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(objectIds));
        propertyValues =
                propertyValues == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(propertyValues));
        propertyValueSets =
                propertyValueSets == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(propertyValueSets));
    }

    /** 속성마다 값 하나만 거는 조건. 값 여러 개(propertyValueSets)는 Agent 목표 유형 찾기만 쓴다. */
    public ObjectSearchAroundRequest(
            List<Long> objectIds,
            Long linkTypeId,
            Direction direction,
            Map<String, String> propertyValues,
            String dateProperty,
            String from,
            String to) {
        this(objectIds, linkTypeId, direction, propertyValues, dateProperty, from, to, Map.of());
    }

    public ObjectSearchAroundRequest(List<Long> objectIds, Long linkTypeId, Direction direction) {
        this(objectIds, linkTypeId, direction, Map.of(), null, null, null, Map.of());
    }
}
