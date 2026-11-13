package com.axcore.platform.ontology.repository;

/**
 * 객체 SQL이 함께 쓰는 표시 이름·원천 키 가림 조각(S15P31C106-205, 384에서 객체 조회와 공통 읽기 계층이 같이 쓰도록 분리). 두 조각 모두 {@code
 * oi}(object_instance) 별칭과 {@code :maskedTypeIds}(민감 값을 가릴 유형 ID 배열) 매개변수를 전제로 한다.
 *
 * <ul>
 *   <li>{@link #SOURCE_SCOPE_JOINS}: {@code scope.can_view_restricted}와, 민감 속성에 매핑된 원천 키 이름 {@code
 *       source_mask.masked_values}를 만든다.
 *   <li>{@link #DISPLAY_JOINS}: 위에 더해 {@code ot}(object_type)를 잇고, 가려야 하면 표시 이름을 대표 속성(일반 속성일
 *       때)·가리지 않는 원천 키·{@code #id} 순으로 다시 만든 {@code shown.display_name}을 낸다.
 * </ul>
 */
final class ObjectDisplaySql {

    private ObjectDisplaySql() {}

    static final String SOURCE_SCOPE_JOINS =
            """
            cross join lateral (
                select oi.object_type_id <> all(:maskedTypeIds) as can_view_restricted
            ) scope
            left join lateral (
                select jsonb_object_agg(raw_key.key, 'null'::jsonb) as masked_values
                from jsonb_each(oi.source_key) raw_key
                where not scope.can_view_restricted
                  and not coalesce((
                      select bool_and(pd.sensitivity = 'NORMAL')
                      from property_source_mapping pm
                      join source_column sc on sc.id = pm.source_column_id
                      join property_definition pd on pd.id = pm.property_definition_id
                      where pm.source_mapping_id = oi.source_mapping_id
                        and pd.object_type_id = oi.object_type_id
                        and sc.column_name = raw_key.key
                  ), false)
            ) source_mask on true
            """;

    // 수집 시의 표시 이름은 매핑 변경 후에도 남을 수 있어 제한 사용자에게는 읽을 수 있는 값으로 다시 만든다.
    static final String DISPLAY_JOINS =
            """
            join object_type ot on ot.id = oi.object_type_id
            left join property_definition display_property on display_property.id = ot.display_property_id
            """
                    + SOURCE_SCOPE_JOINS
                    + """
            cross join lateral (
                select case
                    when scope.can_view_restricted
                         or (oi.source_mapping_id is null
                             and display_property.sensitivity is distinct from 'RESTRICTED')
                    then oi.display_name
                    else left(coalesce(
                        case when display_property.sensitivity = 'NORMAL'
                            then nullif(btrim(oi.properties ->> display_property.code), '') end,
                        (select string_agg(name_key.value, ' / ' order by name_key.key)
                         from jsonb_each_text(oi.source_key) name_key
                         where not coalesce(jsonb_exists(source_mask.masked_values, name_key.key), false)
                           and nullif(btrim(name_key.value), '') is not null),
                        '#' || oi.id
                    ), 300)
                end as display_name
            ) shown
            """;
}
