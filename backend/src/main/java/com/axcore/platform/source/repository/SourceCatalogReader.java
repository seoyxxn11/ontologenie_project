package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.ResourceKind;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 원천 PostgreSQL에 용도별 계정으로 접속해 그 계정이 읽을 수 있는 표·뷰와 컬럼을 읽는다(연결 검사, FN-SRC-02). 시스템 카탈로그만 조회하고 업무 행은 읽지
 * 않는다. 읽을 수 있는지는 원천 권한(has_table_privilege·has_column_privilege)으로 판단하므로, 원천 권한으로 막은 금지 컬럼은 결과에 나오지
 * 않는다.
 */
@Repository
public class SourceCatalogReader {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 60;
    private static final int STATEMENT_TIMEOUT_MILLIS = 30_000;

    // 시스템 스키마와 파티션 자식 표는 빼고, 스키마 USAGE와 표 또는 컬럼 하나 이상의 SELECT 권한이 있는 표·뷰만 고른다.
    private static final String READABLE_RELATIONS =
            """
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where c.relkind in ('r', 'p', 'v', 'm')
              and not c.relispartition
              and n.nspname not in ('pg_catalog', 'information_schema')
              and n.nspname not like 'pg\\_toast%'
              and n.nspname not like 'pg\\_temp%'
              and has_schema_privilege(n.oid, 'USAGE')
            """;

    // 셋째 칸은 CDC publication에 들어 있는지다. Debezium은 이 publication에 든 표의 변경만 보낸다.
    private static final String RESOURCES_SQL =
            """
            select n.nspname, c.relname, c.relkind,
                   exists (select 1 from pg_publication_tables pt
                           where pt.pubname = ? and pt.schemaname = n.nspname and pt.tablename = c.relname)
            """
                    + READABLE_RELATIONS
                    + """
                      and (has_table_privilege(c.oid, 'SELECT')
                           or exists (select 1 from pg_attribute a
                                      where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
                                        and has_column_privilege(c.oid, a.attnum, 'SELECT')))
                    order by n.nspname, c.relname
                    """;

    private static final String COLUMNS_SQL =
            """
            select n.nspname, c.relname, a.attname, left(format_type(a.atttypid, a.atttypmod), 100),
                   not a.attnotnull, a.attnum, col_description(c.oid, a.attnum),
                   exists (select 1 from pg_index i
                           where i.indrelid = c.oid and i.indisprimary and a.attnum = any(i.indkey)),
                   has_column_privilege(c.oid, a.attnum, 'SELECT')
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            where a.attnum > 0 and not a.attisdropped
              and c.oid in (select c.oid
            """
                    + READABLE_RELATIONS
                    + """
                    )
                    order by n.nspname, c.relname, a.attnum
                    """;

    private final String sslMode;
    private final String cdcPublication;

    public SourceCatalogReader(
            @Value("${axcore.source-check.ssl-mode}") String sslMode,
            @Value("${axcore.source-check.cdc-publication}") String cdcPublication) {
        this.sslMode = sslMode;
        this.cdcPublication = cdcPublication;
    }

    /** 접속하지 못하거나 카탈로그를 읽지 못하면 {@link SQLException}이다. 예외 메시지에는 비밀번호가 들어가지 않지만 화면에는 SQLSTATE만 쓴다. */
    public Catalog read(SourceEndpoint endpoint, SourceAccount account) throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(endpoint.jdbcUrl(), properties(account))) {
            connection.setReadOnly(true);
            Map<String, List<Column>> columns = new LinkedHashMap<>();
            Map<String, List<String>> columnNames = new LinkedHashMap<>();
            readColumns(connection, columns, columnNames);
            List<Resource> resources = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(RESOURCES_SQL)) {
                statement.setString(1, cdcPublication);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1) + "." + rs.getString(2);
                        resources.add(
                                new Resource(
                                        key,
                                        kind(rs.getString(3)),
                                        rs.getBoolean(4),
                                        List.copyOf(columns.getOrDefault(key, List.of())),
                                        List.copyOf(columnNames.getOrDefault(key, List.of()))));
                    }
                }
            }
            return new Catalog(List.copyOf(resources));
        }
    }

    /** 읽을 수 있는 컬럼은 columns에, 권한과 상관없이 표에 있는 컬럼 이름은 columnNames에 담는다. */
    private static void readColumns(
            Connection connection,
            Map<String, List<Column>> columns,
            Map<String, List<String>> columnNames)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COLUMNS_SQL);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1) + "." + rs.getString(2);
                columnNames.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString(3));
                if (!rs.getBoolean(9)) {
                    continue;
                }
                columns.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(
                                new Column(
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getBoolean(5),
                                        rs.getInt(6),
                                        rs.getString(7),
                                        rs.getBoolean(8)));
            }
        }
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
        properties.setProperty("ApplicationName", "axcore-connection-check");
        properties.setProperty(
                "options",
                "-c statement_timeout=%d -c default_transaction_read_only=on"
                        .formatted(STATEMENT_TIMEOUT_MILLIS));
        return properties;
    }

    private static ResourceKind kind(String relkind) {
        return "r".equals(relkind) || "p".equals(relkind) ? ResourceKind.TABLE : ResourceKind.VIEW;
    }

    /** 한 계정이 읽을 수 있는 표·뷰 목록. */
    public record Catalog(List<Resource> resources) {

        public int columnCount() {
            return resources.stream().mapToInt(resource -> resource.columns().size()).sum();
        }
    }

    /**
     * resourceKey는 {스키마}.{이름}이다. published는 CDC publication에 들어 있는지다. columns는 이 계정이 SELECT할 수 있는
     * 컬럼만 담는다. columnNames는 권한과 상관없이 표에 있는 컬럼 이름이라, 컬럼이 원천에서 없어졌는지와 권한만 빠졌는지를 가를 때 쓴다.
     */
    public record Resource(
            String resourceKey,
            ResourceKind kind,
            boolean published,
            List<Column> columns,
            List<String> columnNames) {}

    public record Column(
            String name,
            String dataType,
            boolean nullable,
            int position,
            String comment,
            boolean keyPart) {}
}
