package com.axcore.platform.ontology.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entity가 없는 object_instance에서 속성별 저장 값 종류를 읽는다. Agent 목록 조회(359)가 조건 값을 고를 때 쓴다. 볼 수 있는 유형과 값을 볼 수
 * 있는 속성인지는 {@link com.axcore.platform.ontology.service.PropertyValueSampleService}가 정해 넘긴다.
 */
@Repository
@RequiredArgsConstructor
public class PropertyValueSampleRepository {
    private final JdbcClient jdbc;

    /**
     * 사용 중인 객체에서 속성별 값 종류를 값 순서로 최대 limit + 1개 돌려준다. limit보다 많으면 범주 값이 아니라고 보고 부르는 쪽이 버린다. null·빈
     * 값은 넣지 않는다.
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> distinctValues(
            long objectTypeId, Collection<String> propertyCodes, int limit) {
        if (propertyCodes.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(
                        """
                        select code, value
                        from (
                            select v.code, v.value,
                                   row_number() over (partition by v.code order by v.value) as rn
                            from (
                                select distinct e.key as code, e.value
                                from object_instance oi
                                cross join lateral jsonb_each_text(oi.properties) e
                                where oi.object_type_id = :typeId
                                  and oi.status = 'ACTIVE'
                                  and e.key in (:codes)
                                  and e.value is not null
                                  and e.value <> ''
                            ) v
                        ) ranked
                        where rn <= :limit + 1
                        order by code, value
                        """)
                .param("typeId", objectTypeId)
                .param("codes", propertyCodes)
                .param("limit", limit)
                .query((rs, rowNum) -> Map.entry(rs.getString("code"), rs.getString("value")))
                .list()
                .stream()
                .collect(
                        Collectors.groupingBy(
                                Map.Entry::getKey,
                                LinkedHashMap::new,
                                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /**
     * 주어진 객체에서 속성별 값마다 건수를 센다. 많은 값부터 돌려준다. null·빈 값은 세지 않는다. 볼 수 있는 객체·속성인지는 부르는 쪽이 정한다. Agent 목표
     * 유형 찾기(OBJECT_SET_FIND)가 도착 객체의 상태 값 분포(예: 출하완료 56, 생산중 1)를 보여 줄 때 쓴다.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Long>> countValues(
            Collection<Long> objectIds, Collection<String> propertyCodes) {
        if (objectIds.isEmpty() || propertyCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        select e.key as code, e.value, count(*) as cnt
                        from object_instance oi
                        cross join lateral jsonb_each_text(oi.properties) e
                        where oi.id = any(:ids)
                          and oi.status = 'ACTIVE'
                          and e.key in (:codes)
                          and e.value is not null
                          and e.value <> ''
                        group by e.key, e.value
                        order by e.key, cnt desc, e.value
                        """)
                .param("ids", new SqlArrayValue("bigint", objectIds.toArray()))
                .param("codes", propertyCodes)
                .query(
                        (rs, rowNum) -> {
                            counts.computeIfAbsent(
                                            rs.getString("code"), code -> new LinkedHashMap<>())
                                    .put(rs.getString("value"), rs.getLong("cnt"));
                            return null;
                        })
                .list();
        return counts;
    }
}
