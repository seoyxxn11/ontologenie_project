package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.dto.ObjectLinkGroupResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse.ExpressionKind;
import com.axcore.platform.ontology.dto.ObjectLinkResponse.Origin;
import com.axcore.platform.ontology.dto.ObjectLinkResponse.RelatedObject;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Entity가 없는 object_instance·object_link의 검색, 상세, 활성 관계 조회. */
@Repository
@RequiredArgsConstructor
public class ObjectQueryRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> PROPERTY_NAMES =
            new TypeReference<>() {};
    private static final TypeReference<List<ObjectLinkEvidenceResponse.Comparison>> COMPARISONS =
            new TypeReference<>() {};

    private static final ObjectLinkEvidenceResponse EMPTY_EVIDENCE =
            new ObjectLinkEvidenceResponse(null, null, null, null, null, null, List.of(), false);

    private static final String OBJECTS = "from object_instance oi\n";

    private static final String SOURCE_SCOPE_JOINS = ObjectDisplaySql.SOURCE_SCOPE_JOINS;

    private static final String DISPLAY_JOINS = ObjectDisplaySql.DISPLAY_JOINS;

    private static final String FILTERS =
            """
            where oi.object_type_id in (:visibleTypeIds)
              and oi.status = :status
            """;

    private static final String SEARCH_MATCH =
            """
              (
                  shown.display_name ilike %1$s
                  or exists (
                      select 1
                      from jsonb_each_text(
                          oi.source_key || coalesce(source_mask.masked_values, '{}'::jsonb)
                      ) visible_key
                      where visible_key.value ilike %1$s
                  )
                  or exists (
                      select 1 from property_definition identifier
                      where identifier.object_type_id = oi.object_type_id
                        and identifier.is_identifier
                        and (scope.can_view_restricted or identifier.sensitivity = 'NORMAL')
                        and (oi.properties ->> identifier.code) ilike %1$s
                  )
              )
            """;

    private static final String SEARCH_CONDITION = "and " + SEARCH_MATCH.formatted(":pattern");

    private static final String SEARCH_ORDER =
            "order by case when "
                    + SEARCH_MATCH.formatted(":exactPattern")
                    + "then 0 else 1 end, oi.id ";

    // 속성마다 포함 조건(@>)으로 찾아 properties의 GIN 인덱스(jsonb_path_ops)를 쓴다. 원천 키는 보지 않는다.
    // id 순 limit을 찾기와 같은 단계에 두면 id 순으로 전체를 훑는 계획이 나와서 materialized로 나눈다.
    private static final String PROPERTY_VALUE_MATCHES =
            """
            with matched as materialized (
                select oi.id
                from property_definition pd
                join object_instance oi
                  on oi.object_type_id = pd.object_type_id
                 and oi.properties @> jsonb_build_object(pd.code, cast(:value as text))
                where pd.object_type_id in (:visibleTypeIds)
                  and pd.data_type = 'STRING'
                  and (pd.sensitivity = 'NORMAL' or pd.object_type_id <> all(:maskedTypeIds))
                  and oi.status = 'ACTIVE'
            ), picked as (
                select distinct id from matched order by id limit :limit
            )
            """;

    private static final String SELECT =
            """
            select oi.id, oi.object_type_id, ot.name as object_type_name, shown.display_name,
                   oi.properties::text as properties, oi.status, oi.source_mapping_id,
                   (oi.source_key || coalesce(source_mask.masked_values, '{}'::jsonb))::text as source_key,
                   oi.first_collected_at, oi.last_synced_at,
                   (select case when count(*) = 1 and min(pd.data_type) = 'DATETIME'
                                then min(pd.code) end
                    from property_definition pd
                    where pd.object_type_id = oi.object_type_id and pd.temporal_role = 'AS_OF') as as_of_code
            """;

    private static final String LINK_OBJECTS =
            """
            from object_link l
            join object_instance oi on oi.id = case when l.source_object_id = :id
                                                    then l.target_object_id else l.source_object_id end
            """;

    // 기준 객체의 상태와 권한은 같은 트랜잭션에서 existsActive로 먼저 확인한다.
    private static final String LINK_FILTERS =
            """
            where l.status = 'ACTIVE' and oi.status = 'ACTIVE'
              and ((:includeOut and l.source_object_id = :id)
                   or (:includeIn and l.target_object_id = :id))
              and oi.object_type_id in (:visibleTypeIds)
              and (cast(:linkTypeId as bigint) is null or l.link_type_id = :linkTypeId)
            """;

    // 가림 표시는 공개 응답에 담을 수 있는 문자열 맵이나 비교 조건이 있을 때만 켠다.
    private static final String LINK_EVIDENCE_FIELDS =
            """
            , evidence_source.source_key as evidence_source_key,
              case when evidence_scope.can_view then l.evidence::text end as evidence_payload,
              case when evidence_scope.can_view then comparison_details.items::text end as evidence_comparisons,
              case
                  when evidence_scope.can_view then false
                  when l.origin = 'RULE' then
                      comparison_details.has_items or exists (
                          select 1
                          from (values (l.evidence -> 'rawValues'), (l.evidence -> 'matchedValues')) stored(payload)
                          where jsonb_typeof(stored.payload) = 'object'
                            and stored.payload <> '{}'::jsonb
                            and not jsonb_path_exists(stored.payload, 'strict $.* ? (@.type() != "string")',
                                                      '{}'::jsonb, true)
                      )
                  when l.origin = 'DOCUMENT' then coalesce(
                      jsonb_typeof(l.evidence -> 'quote') = 'string'
                      or jsonb_typeof(l.evidence -> 'event') = 'string'
                      or jsonb_typeof(coalesce(
                          l.evidence -> 'time_expression', l.evidence -> 'timeExpression')) = 'string',
                      false)
                  else false
              end as evidence_restricted
            """;

    // 관계 방향과 관계없이 원천 키는 출발 객체의 것을 사용한다.
    private static final String LINK_EVIDENCE_JOINS =
            """
            join lateral (
                select oi.object_type_id, oi.source_mapping_id, oi.status, scope.can_view_restricted,
                       case when l.origin = 'RULE'
                            then (oi.source_key || coalesce(source_mask.masked_values, '{}'::jsonb))::text
                       end as source_key
                from object_instance oi
            """
                    + SOURCE_SCOPE_JOINS
                    + """
                where oi.id = l.source_object_id
            ) evidence_source on true
            join object_instance evidence_target on evidence_target.id = l.target_object_id
            cross join lateral (
                select evidence_target.object_type_id <> all(:maskedTypeIds) as can_view_restricted
            ) target_scope
            cross join lateral (
                select case
                    when l.origin not in ('RULE', 'DOCUMENT') then false
                    when evidence_source.can_view_restricted and target_scope.can_view_restricted then true
                    when l.origin = 'RULE' then coalesce((
                        select bool_and(
                            src.object_type_id = evidence_source.object_type_id
                            and dst.object_type_id = evidence_target.object_type_id
                            and (evidence_source.can_view_restricted or src.sensitivity = 'NORMAL')
                            and (target_scope.can_view_restricted or dst.sensitivity = 'NORMAL')
                        )
                        from link_mapping_condition c
                        join property_definition src on src.id = c.source_property_id
                        join property_definition dst on dst.id = c.target_property_id
                        where c.link_mapping_id = l.link_mapping_id
                    ), false)
                    else false
                end as can_view
            ) evidence_scope
            left join lateral (
                select jsonb_agg(jsonb_build_object(
                    'sequenceNo', c.sequence_no,
                    'sourceProperty', src.code,
                    'sourceColumn', source_column.column_name,
                    'sourceValue', comparison.source_value,
                    'targetProperty', dst.code,
                    'targetColumn', target_column.column_name,
                    'targetValue', comparison.target_value,
                    'matchedValue', l.evidence -> 'matchedValues' -> (c.sequence_no::text)
                ) order by c.sequence_no) filter (where evidence_scope.can_view) as items,
                       count(*) > 0 as has_items
                from link_mapping_condition c
                join property_definition src on src.id = c.source_property_id
                join property_definition dst on dst.id = c.target_property_id
                left join property_source_mapping source_mapping
                    on evidence_scope.can_view
                   and source_mapping.source_mapping_id = evidence_source.source_mapping_id
                   and source_mapping.property_definition_id = src.id
                left join source_column source_column on source_column.id = source_mapping.source_column_id
                left join property_source_mapping target_mapping
                    on evidence_scope.can_view
                   and target_mapping.source_mapping_id = evidence_target.source_mapping_id
                   and target_mapping.property_definition_id = dst.id
                left join source_column target_column on target_column.id = target_mapping.source_column_id
                cross join lateral (
                    select l.evidence -> 'rawValues' -> (c.sequence_no::text) as source_value,
                           coalesce(l.evidence -> 'targetRawValues' -> (c.sequence_no::text),
                               case when c.target_transforms = '[]'::jsonb
                                    then l.evidence -> 'matchedValues' -> (c.sequence_no::text) end) as target_value
                ) comparison
                where l.origin = 'RULE'
                  and c.link_mapping_id = l.link_mapping_id
                  and jsonb_typeof(comparison.source_value) = 'string'
                  and jsonb_typeof(comparison.target_value) = 'string'
                  and jsonb_typeof(l.evidence -> 'matchedValues' -> (c.sequence_no::text)) = 'string'
            ) comparison_details on true
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<ObjectRow> search(
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            Status status,
            String exactPattern,
            SearchFilters filters,
            Pageable page) {
        if (exactPattern == null) {
            return searchPage(visibleTypeIds, maskedTypeIds, status, null, filters, page);
        }
        try (var jitScope = disableJitForSearch()) {
            return searchPage(visibleTypeIds, maskedTypeIds, status, exactPattern, filters, page);
        }
    }

    private Page<ObjectRow> searchPage(
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            Status status,
            String exactPattern,
            SearchFilters filters,
            Pageable page) {
        String pattern = exactPattern == null ? null : "%" + exactPattern + "%";
        SearchClause conditions = searchConditions(filters);
        String matches =
                OBJECTS
                        + DISPLAY_JOINS
                        + FILTERS
                        + (pattern == null ? "" : SEARCH_CONDITION)
                        + conditions.sql();
        String order = exactPattern == null ? "order by oi.id " : SEARCH_ORDER;
        JdbcClient.StatementSpec pageQuery =
                displayQuery(
                        SELECT + matches + order + "limit :limit offset :offset",
                        visibleTypeIds,
                        maskedTypeIds,
                        status,
                        pattern);
        if (exactPattern != null) {
            pageQuery = pageQuery.param("exactPattern", exactPattern);
        }
        List<ObjectRow> rows =
                pageQuery
                        .params(conditions.parameters())
                        .param("limit", page.getPageSize())
                        .param("offset", page.getOffset())
                        .query((rs, rowNum) -> row(rs))
                        .list();
        JdbcClient.StatementSpec count =
                pattern == null
                        ? scopedQuery(
                                "select count(*) " + OBJECTS + FILTERS + conditions.sql(),
                                visibleTypeIds,
                                status)
                        : displayQuery(
                                "select count(*) " + matches,
                                visibleTypeIds,
                                maskedTypeIds,
                                status,
                                pattern);
        long total = count.params(conditions.parameters()).query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    /** search와 같은 조건(검색어 제외)에 맞는 객체 수(380). */
    public long count(Collection<Long> visibleTypeIds, Status status, SearchFilters filters) {
        SearchClause conditions = searchConditions(filters);
        return scopedQuery(
                        "select count(*) " + OBJECTS + FILTERS + conditions.sql(),
                        visibleTypeIds,
                        status)
                .params(conditions.parameters())
                .query(Long.class)
                .single();
    }

    /** search와 같은 조건(검색어 제외)에 맞는 객체 ID를 id 순으로 limit건까지 돌려준다(380). */
    public List<Long> searchIds(
            Collection<Long> visibleTypeIds, Status status, SearchFilters filters, int limit) {
        SearchClause conditions = searchConditions(filters);
        return scopedQuery(
                        "select oi.id "
                                + OBJECTS
                                + FILTERS
                                + conditions.sql()
                                + "order by oi.id limit :limit",
                        visibleTypeIds,
                        status)
                .params(conditions.parameters())
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    // JSON의 속성 키와 값은 바인딩한다. SQL에 더하는 자료형은 검증된 enum에서만 고른다.
    private static SearchClause searchConditions(SearchFilters filters) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int index = 0; index < filters.values().size(); index++) {
            PropertyFilter filter = filters.values().get(index);
            String key = "filterProperty" + index;
            String value = "filterValue" + index;
            sql.append(" and ").append(propertyExpression(key, filter.type()));
            if (filter.values().size() == 1) {
                sql.append(" = cast(:").append(value).append(" as ");
                sql.append(sqlType(filter.type())).append(")\n");
                parameters.put(value, filter.values().getFirst());
            } else {
                // 값이 여럿이면 그중 하나와 같으면 된다. Agent 조회 도구(객체 목록·객체 집합·목표 유형 찾기)만 쓴다. 076 공개 API는 값 하나다.
                sql.append(" = any(cast(cast(:").append(value).append(" as text[]) as ");
                sql.append(sqlType(filter.type())).append("[]))\n");
                parameters.put(value, new SqlArrayValue("text", filter.values().toArray()));
            }
            parameters.put(key, filter.code());
        }
        PeriodFilter period = filters.period();
        if (period != null) {
            parameters.put("dateProperty", period.code());
            if (period.from() != null) {
                sql.append(" and ")
                        .append(propertyExpression("dateProperty", period.type()))
                        .append(" >= cast(:periodFrom as ")
                        .append(sqlType(period.type()))
                        .append(")\n");
                parameters.put("periodFrom", period.from());
            }
            if (period.to() != null) {
                sql.append(" and ")
                        .append(propertyExpression("dateProperty", period.type()))
                        .append(" <= cast(:periodTo as ")
                        .append(sqlType(period.type()))
                        .append(")\n");
                parameters.put("periodTo", period.to());
            }
        }
        return new SearchClause(sql.toString(), parameters);
    }

    private static String propertyExpression(String keyParameter, PropertyDataType type) {
        String value = "(oi.properties ->> :" + keyParameter + ")";
        if (type == PropertyDataType.STRING) return value;
        String sqlType = sqlType(type);
        // 정의 변경 전의 값이나 잘못 저장된 값이 있어도 검색 전체를 500으로 끝내지 않는다.
        return "(case when pg_input_is_valid("
                + value
                + ", '"
                + sqlType
                + "') then cast("
                + value
                + " as "
                + sqlType
                + ") end)";
    }

    private static String sqlType(PropertyDataType type) {
        return switch (type) {
            case STRING -> "text";
            case INTEGER, DECIMAL -> "numeric";
            case DATE -> "date";
            case TIME -> "time";
            case DATETIME -> "timestamptz";
            case BOOLEAN -> "boolean";
            case JSON -> throw new IllegalArgumentException("JSON 조건은 지원하지 않습니다.");
        };
    }

    private record SearchClause(String sql, Map<String, Object> parameters) {}

    /** 속성 하나의 조건. 값이 여럿이면 그중 하나와 같으면 된다. */
    public record PropertyFilter(String code, PropertyDataType type, List<String> values) {
        public PropertyFilter {
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("조건 값이 없습니다: " + code);
            }
        }

        public PropertyFilter(String code, PropertyDataType type, String value) {
            this(code, type, List.of(value));
        }
    }

    public record PeriodFilter(String code, PropertyDataType type, String from, String to) {}

    public record SearchFilters(List<PropertyFilter> values, PeriodFilter period) {}

    private JitScope disableJitForSearch() {
        if ("off".equals(jdbc.sql("show jit").query(String.class).single())) {
            return () -> {};
        }
        // 검색 비용 추정치가 높아, 결과가 적어도 JIT 컴파일에 시간이 오래 걸린다.
        jdbc.sql("set local jit = off").update();
        // 호출자가 이미 트랜잭션을 시작했을 수 있어 조회를 마치면 원래 값으로 복구한다.
        return () -> jdbc.sql("set local jit = on").update();
    }

    @FunctionalInterface
    private interface JitScope extends AutoCloseable {
        @Override
        void close();
    }

    public List<ObjectRow> findByPropertyValue(
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            String value,
            int limit) {
        try (var jitScope = disableJitForSearch()) {
            return withDisplayScope(
                            jdbc.sql(
                                    PROPERTY_VALUE_MATCHES
                                            + SELECT
                                            + "from picked\n"
                                            + "join object_instance oi on oi.id = picked.id\n"
                                            + DISPLAY_JOINS
                                            + "order by oi.id"),
                            maskedTypeIds)
                    .param("visibleTypeIds", visibleTypeIds)
                    .param("value", value)
                    .param("limit", limit)
                    .query((rs, rowNum) -> row(rs))
                    .list();
        }
    }

    /** ids 중 볼 수 있는 유형의 활성 객체를 076 검색 결과와 같은 형태로 id 순으로 돌려준다(380). */
    public List<ObjectRow> findByIds(
            Collection<Long> ids, Collection<Long> visibleTypeIds, Collection<Long> maskedTypeIds) {
        return withDisplayScope(
                        jdbc.sql(
                                SELECT
                                        + OBJECTS
                                        + DISPLAY_JOINS
                                        + """
                                        where oi.id = any(:ids)
                                          and oi.object_type_id in (:visibleTypeIds)
                                          and oi.status = 'ACTIVE'
                                        order by oi.id
                                        """),
                        maskedTypeIds)
                .param("ids", new SqlArrayValue("bigint", ids.toArray()))
                .param("visibleTypeIds", visibleTypeIds)
                .query((rs, rowNum) -> row(rs))
                .list();
    }

    /**
     * 한 유형의 문자 속성에서 공백을 빼면 value와 같은 저장 값을 값 순서로 limit건까지 돌려준다(378). "정비 중"으로 물어도 저장 값 "정비중"을 찾기 위해
     * 쓴다. 공백 비교는 PostgreSQL 정규식 하나로 해 저장 값과 입력 값에 같은 기준을 쓴다.
     */
    public List<String> findSpacingVariants(
            long typeId, String code, String value, Status status, int limit) {
        return jdbc.sql(
                        """
                        select distinct oi.properties ->> :code as stored
                        from object_instance oi
                        where oi.object_type_id = :typeId
                          and oi.status = :status
                          and regexp_replace(oi.properties ->> :code, '\\s', '', 'g')
                              = regexp_replace(:value, '\\s', '', 'g')
                        order by stored
                        limit :limit
                        """)
                .param("typeId", typeId)
                .param("code", code)
                .param("value", value)
                .param("status", status.name())
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public Optional<DetailRow> findDetail(
            long id,
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            boolean includeDeleted) {
        return withDisplayScope(
                        jdbc.sql(
                                SELECT
                                        + """
                                        , ds.name as source_system_name, sr.resource_key,
                                          coalesce((
                                              select jsonb_object_agg(pd.code, pd.name)
                                              from property_definition pd
                                              where pd.object_type_id = oi.object_type_id
                                                and jsonb_exists(oi.properties, pd.code)
                                          ), '{}'::jsonb)::text as property_names
                                        """
                                        + OBJECTS
                                        + DISPLAY_JOINS
                                        + """
                                        left join object_source_mapping osm on osm.id = oi.source_mapping_id
                                        left join source_resource sr on sr.id = osm.source_resource_id
                                        left join data_source ds on ds.id = sr.data_source_id
                                        where oi.id = :id and oi.object_type_id in (:visibleTypeIds)
                                          and (:includeDeleted or oi.status = 'ACTIVE')
                                        """),
                        maskedTypeIds)
                .param("id", id)
                .param("visibleTypeIds", visibleTypeIds)
                .param("includeDeleted", includeDeleted)
                .query(
                        (rs, rowNum) ->
                                new DetailRow(
                                        row(rs),
                                        json.readValue(
                                                rs.getString("property_names"), PROPERTY_NAMES),
                                        rs.getString("source_system_name"),
                                        rs.getString("resource_key")))
                .optional();
    }

    public boolean existsActive(long id, Collection<Long> visibleTypeIds) {
        return scopedQuery(
                        "select exists (select 1 " + OBJECTS + FILTERS + " and oi.id = :id)",
                        visibleTypeIds,
                        Status.ACTIVE)
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public Page<ObjectLinkResponse> links(
            long id,
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            Long linkTypeId,
            Direction direction,
            Pageable page) {
        String select =
                """
                select l.id, l.link_type_id, lt.name as link_type_name,
                       l.source_object_id, l.target_object_id, l.origin, l.status,
                       l.link_mapping_id, l.confirmed_by,
                       case when l.origin = 'DOCUMENT' then l.evidence ->> 'expression_kind' end as expression_kind,
                       oi.id as related_id, oi.object_type_id, ot.name as object_type_name, shown.display_name
                """;
        List<ObjectLinkResponse> rows =
                withDisplayScope(
                                linkQuery(
                                        select
                                                + LINK_EVIDENCE_FIELDS
                                                + LINK_OBJECTS
                                                + "join link_type lt on lt.id = l.link_type_id\n"
                                                + DISPLAY_JOINS
                                                + LINK_EVIDENCE_JOINS
                                                + LINK_FILTERS
                                                + "order by l.id limit :limit offset :offset",
                                        id,
                                        visibleTypeIds,
                                        linkTypeId,
                                        direction),
                                maskedTypeIds)
                        .param("limit", page.getPageSize())
                        .param("offset", page.getOffset())
                        .query((rs, rowNum) -> linkRow(rs))
                        .list();
        long total =
                linkQuery(
                                "select count(*) " + LINK_OBJECTS + LINK_FILTERS,
                                id,
                                visibleTypeIds,
                                linkTypeId,
                                direction)
                        .query(Long.class)
                        .single();
        return new PageImpl<>(rows, page, total);
    }

    // 078과 같은 조건으로 세어, 묶음 건수가 같은 linkTypeId·direction으로 부른 078의 totalElements와 맞게 한다.
    public List<ObjectLinkGroupResponse> linkGroups(long id, Collection<Long> visibleTypeIds) {
        return linkQuery(
                        """
                        select l.link_type_id, lt.name as link_type_name,
                               case when l.source_object_id = :id then 'OUT' else 'IN' end as direction,
                               related_type.id as related_type_id, related_type.name as related_type_name,
                               count(*) as link_count
                        """
                                + LINK_OBJECTS
                                + """
                                join link_type lt on lt.id = l.link_type_id
                                join object_type related_type
                                  on related_type.id = case when l.source_object_id = :id
                                                            then lt.target_type_id else lt.source_type_id end
                                """
                                + LINK_FILTERS
                                + """
                                group by l.link_type_id, lt.name, direction, related_type.id, related_type.name
                                order by link_count desc, l.link_type_id, direction desc
                                """,
                        id,
                        visibleTypeIds,
                        null,
                        Direction.BOTH)
                .query(
                        (rs, rowNum) ->
                                new ObjectLinkGroupResponse(
                                        rs.getLong("link_type_id"),
                                        rs.getString("link_type_name"),
                                        Direction.valueOf(rs.getString("direction")),
                                        rs.getLong("related_type_id"),
                                        rs.getString("related_type_name"),
                                        rs.getLong("link_count")))
                .list();
    }

    /**
     * ids 중 볼 수 있는 유형의 활성 객체만 객체 ID → 유형 ID로 돌려준다. Search Around(379)가 기준 객체를 쿼리 한 번으로 확인할 때 쓴다.
     */
    public Map<Long, Long> findActiveObjectTypes(
            Collection<Long> ids, Collection<Long> visibleTypeIds) {
        Map<Long, Long> types = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        select oi.id, oi.object_type_id
                        from object_instance oi
                        where oi.id = any(:ids)
                          and oi.status = 'ACTIVE'
                          and oi.object_type_id in (:visibleTypeIds)
                        """)
                .param("ids", new SqlArrayValue("bigint", ids.toArray()))
                .param("visibleTypeIds", visibleTypeIds)
                .query(
                        rs -> {
                            types.put(rs.getLong("id"), rs.getLong("object_type_id"));
                        });
        return types;
    }

    /**
     * 기준 객체에서 linkTypeId 활성 관계로 한 홉 건넌 relatedTypeId 유형의 활성 객체 ID를 중복 없이 id 순으로 limit건까지 돌려준다(379).
     * 관계·상대 객체 상태는 078의 LINK_FILTERS와 같고, filters는 076과 같은 SQL로 상대 객체에 건다. 기준 객체의 상태·권한과 상대 유형의 권한은
     * 호출자가 같은 트랜잭션에서 먼저 확인한다.
     */
    public List<Long> searchAround(
            Collection<Long> baseIds,
            long linkTypeId,
            Direction direction,
            long relatedTypeId,
            SearchFilters filters,
            int limit) {
        SearchClause conditions = searchConditions(filters);
        return jdbc.sql(
                        """
                        select oi.id
                        from (
                            select l.target_object_id as related_id
                            from object_link l
                            where :includeOut and l.link_type_id = :linkTypeId and l.status = 'ACTIVE'
                              and l.source_object_id = any(:baseIds)
                            union
                            select l.source_object_id
                            from object_link l
                            where :includeIn and l.link_type_id = :linkTypeId and l.status = 'ACTIVE'
                              and l.target_object_id = any(:baseIds)
                        ) related
                        join object_instance oi on oi.id = related.related_id
                        where oi.status = 'ACTIVE' and oi.object_type_id = :relatedTypeId
                        """
                                + conditions.sql()
                                + "order by oi.id limit :limit")
                .param("baseIds", new SqlArrayValue("bigint", baseIds.toArray()))
                .param("linkTypeId", linkTypeId)
                .param("includeOut", direction != Direction.IN)
                .param("includeIn", direction != Direction.OUT)
                .param("relatedTypeId", relatedTypeId)
                .params(conditions.parameters())
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public Optional<ObjectLinkEvidenceResponse> findLinkEvidence(
            long objectId,
            long linkId,
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds) {
        return withDisplayScope(
                        jdbc.sql(
                                "select l.origin\n"
                                        + LINK_EVIDENCE_FIELDS
                                        + "from object_link l\n"
                                        + LINK_EVIDENCE_JOINS
                                        + """
                                        where l.id = :linkId and l.status = 'ACTIVE'
                                          and :objectId in (l.source_object_id, l.target_object_id)
                                          and evidence_source.status = 'ACTIVE'
                                          and evidence_target.status = 'ACTIVE'
                                          and evidence_source.object_type_id in (:visibleTypeIds)
                                          and evidence_target.object_type_id in (:visibleTypeIds)
                                        """),
                        maskedTypeIds)
                .param("objectId", objectId)
                .param("linkId", linkId)
                .param("visibleTypeIds", visibleTypeIds)
                .query((rs, rowNum) -> linkEvidence(rs, Origin.valueOf(rs.getString("origin"))))
                .optional();
    }

    private JdbcClient.StatementSpec linkQuery(
            String sql,
            long id,
            Collection<Long> visibleTypeIds,
            Long linkTypeId,
            Direction direction) {
        return jdbc.sql(sql)
                .param("id", id)
                .param("visibleTypeIds", visibleTypeIds)
                .param("linkTypeId", linkTypeId, Types.BIGINT)
                .param("includeOut", direction != Direction.IN)
                .param("includeIn", direction != Direction.OUT);
    }

    private ObjectLinkResponse linkRow(ResultSet rs) throws SQLException {
        String expression = rs.getString("expression_kind");
        Origin origin = Origin.valueOf(rs.getString("origin"));
        return new ObjectLinkResponse(
                rs.getLong("id"),
                rs.getLong("link_type_id"),
                rs.getString("link_type_name"),
                rs.getLong("source_object_id"),
                rs.getLong("target_object_id"),
                new RelatedObject(
                        rs.getLong("related_id"),
                        rs.getString("display_name"),
                        rs.getLong("object_type_id"),
                        rs.getString("object_type_name")),
                origin,
                rs.getString("status"),
                rs.getObject("link_mapping_id", Long.class),
                linkEvidence(rs, origin),
                expression == null ? null : ExpressionKind.valueOf(expression),
                rs.getObject("confirmed_by", Long.class));
    }

    private ObjectLinkEvidenceResponse linkEvidence(ResultSet rs, Origin origin)
            throws SQLException {
        String stored = rs.getString("evidence_payload");
        Map<String, Object> payload =
                stored == null ? Map.of() : json.readValue(stored, JSON_OBJECT);
        boolean restricted = rs.getBoolean("evidence_restricted");
        return switch (origin) {
            case RULE -> {
                String sourceKey = rs.getString("evidence_source_key");
                String comparisons = rs.getString("evidence_comparisons");
                yield new ObjectLinkEvidenceResponse(
                        sourceKey == null ? null : json.readValue(sourceKey, JSON_OBJECT),
                        comparisonValue(payload.get("rawValues")),
                        comparisonValue(payload.get("matchedValues")),
                        null,
                        null,
                        null,
                        comparisons == null ? List.of() : json.readValue(comparisons, COMPARISONS),
                        restricted);
            }
            case DOCUMENT ->
                    new ObjectLinkEvidenceResponse(
                            null,
                            null,
                            null,
                            textValue(payload.get("quote")),
                            textValue(payload.get("event")),
                            textValue(
                                    payload.getOrDefault(
                                            "time_expression", payload.get("timeExpression"))),
                            List.of(),
                            restricted);
            case MANUAL -> EMPTY_EVIDENCE;
        };
    }

    private static Object comparisonValue(Object value) {
        if (!(value instanceof Map<?, ?> values)
                || values.isEmpty()
                || !values.values().stream().allMatch(String.class::isInstance)) {
            return null;
        }
        return values.size() == 1 ? values.values().iterator().next() : values;
    }

    private static String textValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private JdbcClient.StatementSpec displayQuery(
            String sql,
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            Status status,
            String pattern) {
        JdbcClient.StatementSpec query =
                withDisplayScope(scopedQuery(sql, visibleTypeIds, status), maskedTypeIds);
        return pattern == null ? query : query.param("pattern", pattern);
    }

    private static JdbcClient.StatementSpec withDisplayScope(
            JdbcClient.StatementSpec query, Collection<Long> maskedTypeIds) {
        return query.param("maskedTypeIds", new SqlArrayValue("bigint", maskedTypeIds.toArray()));
    }

    private JdbcClient.StatementSpec scopedQuery(
            String sql, Collection<Long> visibleTypeIds, Status status) {
        return jdbc.sql(sql).param("visibleTypeIds", visibleTypeIds).param("status", status.name());
    }

    private ObjectRow row(ResultSet rs) throws SQLException {
        String sourceKey = rs.getString("source_key");
        OffsetDateTime lastSyncedAt = rs.getObject("last_synced_at", OffsetDateTime.class);
        return new ObjectRow(
                rs.getLong("id"),
                rs.getLong("object_type_id"),
                rs.getString("object_type_name"),
                rs.getString("display_name"),
                json.readValue(rs.getString("properties"), JSON_OBJECT),
                Status.valueOf(rs.getString("status")),
                rs.getObject("source_mapping_id", Long.class),
                sourceKey == null ? null : json.readValue(sourceKey, JSON_OBJECT),
                rs.getObject("first_collected_at", OffsetDateTime.class).toInstant(),
                lastSyncedAt == null ? null : lastSyncedAt.toInstant(),
                rs.getString("as_of_code"));
    }

    public record DetailRow(
            ObjectRow object,
            Map<String, String> propertyNames,
            String sourceSystemName,
            String sourceResourceKey) {}

    public record ObjectRow(
            long id,
            long objectTypeId,
            String objectTypeName,
            String displayName,
            Map<String, Object> properties,
            Status status,
            Long sourceMappingId,
            Map<String, Object> sourceKey,
            Instant firstCollectedAt,
            Instant lastSyncedAt,
            String asOfCode) {}
}
