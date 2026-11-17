package com.axcore.platform.source.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * POSTGRES 원천의 접속 주소(data_source.connection_config의 host·port·database). 값은 관리자가 넣으므로 JDBC 주소에 끼워
 * 넣기 전에 형식을 확인한다.
 */
public record SourceEndpoint(String host, int port, String database) {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]{1,63}");

    /** 형식이 틀리면 화면에 보여 줄 수 있는 메시지로 {@link IllegalArgumentException}을 던진다. 메시지에 설정 값은 넣지 않는다. */
    public static SourceEndpoint from(Map<String, Object> config) {
        if (!(config.get("host") instanceof String host) || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("접속 설정의 host가 없거나 형식이 맞지 않습니다.");
        }
        int port = port(config.get("port"));
        if (!(config.get("database") instanceof String database)
                || !DATABASE_PATTERN.matcher(database).matches()) {
            throw new IllegalArgumentException("접속 설정의 database가 없거나 형식이 맞지 않습니다.");
        }
        return new SourceEndpoint(host, port, database);
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
    }

    private static int port(Object value) {
        long port;
        if (value instanceof Number number && number.doubleValue() == number.longValue()) {
            port = number.longValue();
        } else if (value instanceof String text && text.matches("\\d{1,5}")) {
            port = Long.parseLong(text);
        } else {
            port = 0;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("접속 설정의 port가 없거나 형식이 맞지 않습니다.");
        }
        return (int) port;
    }
}
