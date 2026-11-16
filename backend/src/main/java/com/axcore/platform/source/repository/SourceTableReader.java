package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKeyText;
import com.axcore.platform.source.domain.SourceRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 PostgreSQL 테이블의 행을 전부 읽는다(전체 읽기, Jira 197). 원천에는 아무것도 쓰지 않는다.
 *
 * <p>모든 테이블을 REPEATABLE READ 읽기 전용 트랜잭션 하나에서 읽는다. 첫 문장에서 {@code pg_current_snapshot()}을 받으므로 그 스냅샷이
 * 곧 모든 SELECT가 본 스냅샷이다. CDC 이벤트와의 경계는 이 스냅샷으로 판단한다(2026-11-05 결정, V11).
 *
 * <p>값은 컬럼 형식에 맞는 Java 값으로 읽는다: 정수 Long, numeric BigDecimal(NaN·무한대는 Double), 실수 Double, boolean,
 * date LocalDate, time LocalTime, timestamp LocalDateTime, timestamptz OffsetDateTime, json·jsonb
 * 읽은 JSON 값, 그 밖은 문자열. 원천 키 컬럼은 그 값을 {@link SourceKeyText} 형식 문자열로도 만든다(json·jsonb 키는 원천이 정규화한
 * 텍스트). 세션 시간대에 따라 바뀌는 PostgreSQL 텍스트 출력은 키로 쓰지 않는다.
 */
@Repository
public class SourceTableReader {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 600;
    private static final int STATEMENT_TIMEOUT_MILLIS = 300_000;
    private static final int FETCH_SIZE = 1_000;

    private static final String SNAPSHOT_SQL =
            "select pg_current_snapshot()::text, (pg_current_wal_lsn() - '0/0'::pg_lsn)::bigint";

    // 카탈로그의 resource_key({스키마}.{이름})로 실제 표를 찾고 이름을 원천이 직접 따옴표 처리한다.
    private static final String RELATION_SQL =
            """
            select quote_ident(n.nspname) || '.' || quote_ident(c.relname)
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname || '.' || c.relname = ? and c.relkind in ('r', 'p', 'v', 'm')
            """;

    private static final String READABLE_COLUMNS_SQL =
            """
            select attname from pg_attribute
            where attrelid = cast(? as regclass) and attnum > 0 and not attisdropped
              and has_column_privilege(attrelid, attnum, 'SELECT')
            """;

    private final String sslMode;
    private final JsonMapper json;

    public SourceTableReader(
            @Value("${axcore.source-check.ssl-mode}") String sslMode, JsonMapper json) {
        this.sslMode = sslMode;
        this.json = json;
    }

    /**
     * 테이블들을 한 스냅샷으로 읽는다. 행이 maxRows를 넘으면 {@link TooManyRowsException}이다. 접속·읽기 실패는 {@link
     * SQLException}이다.
     */
    public Result read(
            SourceEndpoint endpoint, SourceAccount account, List<Table> tables, int maxRows)
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(endpoint.jdbcUrl(), properties(account))) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                String snapshot;
                long lsn;
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery(SNAPSHOT_SQL)) {
                    rs.next();
                    snapshot = rs.getString(1);
                    lsn = rs.getLong(2);
                }
                Map<Long, List<SourceRow>> rows = new HashMap<>();
                int total = 0;
                for (Table table : tables) {
                    List<SourceRow> tableRows = readTable(connection, table, maxRows - total, null);
                    total += tableRows.size();
                    rows.put(table.resourceId(), tableRows);
                }
                return new Result(snapshot, lsn, rows);
            } finally {
                connection.rollback();
            }
        }
    }

    /** 매핑 미리보기(040)는 지정한 컬럼의 앞 행 몇 개를 읽는다. 순서는 정하지 않는다. 읽기 전용 트랜잭션에서 읽고 되돌린다. */
    public List<SourceRow> sample(
            SourceEndpoint endpoint, SourceAccount account, Table table, int limit)
            throws SQLException {
        return sample(endpoint, account, table, limit, false).rows();
    }

    /** 원천 표본(016)은 현재 계정이 읽을 수 있는 컬럼만 선택하고 그 목록과 행을 반환한다. */
    public Sample sampleReadable(
            SourceEndpoint endpoint, SourceAccount account, Table table, int limit)
            throws SQLException {
        return sample(endpoint, account, table, limit, true);
    }

    private Sample sample(
            SourceEndpoint endpoint,
            SourceAccount account,
            Table table,
            int limit,
            boolean readableOnly)
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(endpoint.jdbcUrl(), properties(account))) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try {
                String relation = relation(connection, table.resourceKey());
                Table selected = table;
                if (readableOnly) {
                    var readable = readableColumns(connection, relation);
                    var columns = table.columns().stream().filter(readable::contains).toList();
                    if (columns.isEmpty()) {
                        throw new SQLException("no readable sample columns", "42501");
                    }
                    selected =
                            new Table(
                                    table.resourceId(),
                                    table.resourceKey(),
                                    columns,
                                    table.keyColumns());
                }
                return new Sample(
                        selected.columns(),
                        readTable(connection, relation, selected, limit, limit));
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * 제안 검토 때 제한 컬럼의 값 확인(447). 한 컬럼의 서로 다른 값 limit개(문자열, 이름순)와 전체·빈 값·서로 다른 값의 수만 읽는다. 키 컬럼이나 같은
     * 행의 다른 컬럼은 읽지 않으므로 어느 행의 값인지 알 수 없다. 읽기 전용 트랜잭션에서 읽고 되돌린다. 계정이 그 컬럼을 읽을 수 없으면 42501이다.
     *
     * @param column 카탈로그(source_column)의 컬럼 이름
     */
    public ValueSummary distinctValues(
            SourceEndpoint endpoint,
            SourceAccount account,
            String resourceKey,
            String column,
            int limit)
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(endpoint.jdbcUrl(), properties(account))) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try {
                String relation = relation(connection, resourceKey);
                if (!readableColumns(connection, relation).contains(column)) {
                    throw new SQLException("column is not readable", "42501");
                }
                String quoted = quote(column);
                long total;
                long filled;
                long distinct;
                try (PreparedStatement statement =
                                connection.prepareStatement(
                                        "select count(*), count(%1$s), count(distinct %1$s) from %2$s"
                                                .formatted(quoted, relation));
                        ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                    filled = rs.getLong(2);
                    distinct = rs.getLong(3);
                }
                List<String> values = new ArrayList<>();
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                ("select distinct cast(%1$s as text) as v from %2$s"
                                                + " where %1$s is not null order by v limit ?")
                                        .formatted(quoted, relation))) {
                    statement.setInt(1, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            values.add(rs.getString(1));
                        }
                    }
                }
                return new ValueSummary(values, total, total - filled, distinct);
            } finally {
                connection.rollback();
            }
        }
    }

    private static Set<String> readableColumns(Connection connection, String relation)
            throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(READABLE_COLUMNS_SQL)) {
            statement.setString(1, relation);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * 원천의 지금 {@code pg_current_snapshot()} xmax(다음에 발급될 트랜잭션 번호, xid8). CDC를 처음 소비할 때 epoch 복원
     * 기준값이다(198-6). 번호를 발급하지 않는 함수라 원천에 쓰지 않는다.
     */
    public long currentXmax(SourceEndpoint endpoint, SourceAccount account) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(endpoint.jdbcUrl(), properties(account));
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "select pg_snapshot_xmax(pg_current_snapshot())::text")) {
            rs.next();
            return Long.parseLong(rs.getString(1));
        }
    }

    private List<SourceRow> readTable(
            Connection connection, Table table, int remaining, Integer limit) throws SQLException {
        return readTable(
                connection, relation(connection, table.resourceKey()), table, remaining, limit);
    }

    /**
     * 표 하나를 읽는다. 표·컬럼 이름은 SQL 문법상 바인딩 변수로 넘길 수 없어 문자열로 만든다. 사용자 입력은 들어가지 않는다: 표 이름은 원천이 {@code
     * quote_ident}로 따옴표 처리한 값({@link #relation}, 카탈로그 키는 바인딩 변수로 넘김)이고, 컬럼 이름은 카탈로그 값을 {@link
     * #quote}로 감싼 식별자다. 그래서 SonarQube java:S2077(동적 SQL)을 이 메서드에서만 끈다(Jira 332).
     *
     * <p>행 수 제한은 늘 바인딩 변수 하나({@code limit ?})로 넘긴다. 전체 읽기는 NULL을 넘기고, PostgreSQL은 {@code LIMIT
     * NULL}을 제한 없음으로 처리한다. 전체 읽기와 미리보기가 같은 SQL 모양이라 매개변수 수가 경로마다 달라지지 않는다(java:S2695).
     */
    @SuppressWarnings("java:S2077")
    private List<SourceRow> readTable(
            Connection connection, String relation, Table table, int remaining, Integer limit)
            throws SQLException {
        String sql =
                "select "
                        + table.columns().stream()
                                .map(SourceTableReader::quote)
                                .collect(Collectors.joining(", "))
                        + " from "
                        + relation
                        + " limit ?";
        List<SourceRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setObject(1, limit, Types.INTEGER);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    if (rows.size() >= remaining) {
                        throw new TooManyRowsException();
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    Map<String, String> keyTexts = new LinkedHashMap<>();
                    for (int i = 1; i <= table.columns().size(); i++) {
                        String column = table.columns().get(i - 1);
                        String typeName = meta.getColumnTypeName(i);
                        Object value = value(rs, i, typeName);
                        values.put(column, value);
                        if (table.keyColumns().contains(column)) {
                            keyTexts.put(column, keyText(rs, i, typeName, value));
                        }
                    }
                    rows.add(new SourceRow(values, keyTexts));
                }
            }
        }
        return rows;
    }

    private static String relation(Connection connection, String resourceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELATION_SQL)) {
            statement.setString(1, resourceKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("relation not found", "42P01");
                }
                String relation = rs.getString(1);
                if (rs.next()) {
                    throw new SQLException("relation name is ambiguous", "42P09");
                }
                return relation;
            }
        }
    }

    private Object value(ResultSet rs, int index, String typeName) throws SQLException {
        Object value =
                switch (typeName) {
                    case "int2", "int4", "int8" -> rs.getLong(index);
                    case "numeric" -> numeric(rs.getString(index));
                    case "float4", "float8" -> rs.getDouble(index);
                    case "bool" -> rs.getBoolean(index);
                    case "date" -> rs.getObject(index, LocalDate.class);
                    case "time" -> rs.getObject(index, LocalTime.class);
                    case "timestamp" -> rs.getObject(index, LocalDateTime.class);
                    case "timestamptz" -> rs.getObject(index, OffsetDateTime.class);
                    case "json", "jsonb" -> {
                        String text = rs.getString(index);
                        yield text == null ? null : json.readValue(text, Object.class);
                    }
                    default -> rs.getString(index);
                };
        return rs.wasNull() ? null : value;
    }

    /**
     * numeric 텍스트를 값으로 바꾼다. {@code getBigDecimal}은 NaN·Infinity에서 예외(22003)를 던져 실행 전체가 실패하므로 쓰지
     * 않는다. NaN·무한대는 Double로 두고, 속성 변환이 그 속성만 뺀다.
     */
    private static Object numeric(String text) {
        if (text == null) {
            return null;
        }
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> new BigDecimal(text);
        };
    }

    private static String keyText(ResultSet rs, int index, String typeName, Object value)
            throws SQLException {
        if (value == null) {
            return null;
        }
        if (typeName.equals("json") || typeName.equals("jsonb")) {
            return rs.getString(index);
        }
        return SourceKeyText.of(value);
    }

    // 컬럼 이름은 카탈로그(source_column)에서 온 값이다. 큰따옴표를 겹쳐 식별자로만 쓰이게 한다.
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private Properties properties(SourceAccount account) {
        Properties properties = new Properties();
        properties.setProperty("user", account.username());
        properties.setProperty("password", account.password());
        properties.setProperty("sslmode", sslMode);
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        properties.setProperty("readOnly", "true");
        properties.setProperty("ApplicationName", "axcore-full-load");
        properties.setProperty(
                "options",
                "-c statement_timeout=%d -c default_transaction_read_only=on"
                        .formatted(STATEMENT_TIMEOUT_MILLIS));
        return properties;
    }

    /**
     * @param columns SELECT할 컬럼(카탈로그 이름)
     * @param keyColumns 키 문자열({@link SourceKeyText})도 만들 원천 키 컬럼
     */
    public record Table(
            long resourceId, String resourceKey, List<String> columns, Set<String> keyColumns) {

        public Table {
            columns = List.copyOf(columns);
            keyColumns = Set.copyOf(keyColumns);
        }
    }

    /**
     * @param snapshot 읽기에 쓴 스냅샷({@code xmin:xmax:xip,...})
     * @param lsn 읽기 시점의 원천 WAL 위치(참고값)
     * @param rows 원천 테이블 ID → 행
     */
    public record Result(String snapshot, long lsn, Map<Long, List<SourceRow>> rows) {}

    public record Sample(List<String> columns, List<SourceRow> rows) {}

    /**
     * 한 컬럼의 값 요약(447).
     *
     * @param values 서로 다른 값(빈 값 제외, 문자열, 이름순, 최대 limit개)
     * @param totalCount 전체 행 수
     * @param emptyCount 값이 비어 있는(null) 행 수
     * @param distinctCount 서로 다른 값의 수(빈 값 제외)
     */
    public record ValueSummary(
            List<String> values, long totalCount, long emptyCount, long distinctCount) {

        public ValueSummary {
            values = List.copyOf(values);
        }
    }

    /** 한 번에 읽을 수 있는 행 수를 넘었다. */
    public static class TooManyRowsException extends RuntimeException {

        TooManyRowsException() {
            super("too many source rows");
        }
    }
}
