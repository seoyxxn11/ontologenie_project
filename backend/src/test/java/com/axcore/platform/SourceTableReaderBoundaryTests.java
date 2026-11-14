package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 원천 DB의 업무 행을 직접 읽는 {@code SourceTableReader}를 정해진 곳에서만 쓰는지 확인한다(S15P31C106-405). 원천 데이터 정책(금지
 * 표·컬럼, 민감 컬럼)은 읽는 쪽이 직접 적용해야 동작하므로, 새 코드가 이 읽기 도구를 쓰면 정책을 빼먹은 채 원천 값을 들일 수 있다. 그래서 쓰는 파일을 아래 목록으로
 * 고정하고, 목록에 없는 파일이 쓰거나 목록의 파일이 더는 쓰지 않으면 실패한다. 이 도구를 거치지 않고 원천에 직접 접속하는 곳도 따로 고정한다.
 *
 * <p>원천 계정 권한(infra/source-db 허용 목록)이 먼저 막고, 이 목록은 플랫폼 안의 두 번째 방어다.
 *
 * <p>찾는 법: 주석을 지운 소스에서 이름이 단어로 나오면 쓰는 것으로 본다(import, 필드, 제네릭, {@code SourceTableReader.class}, 메서드
 * 참조). 소스를 앞에서부터 한 번 훑어 문자열·문자 리터럴과 주석을 구분하므로, 문자열 안의 주석 표시(URL 등)가 코드를 지우지 않는다. 원천 접속은 접속
 * 주소({@code SourceEndpoint.jdbcUrl()})를 쓰는 곳과 드라이버·데이터소스를 직접 다루는 표시로 찾는다. 한계: 리플렉션으로 이름 문자열을 조립하면
 * 찾지 못한다.
 */
class SourceTableReaderBoundaryTests {

    private static final Path MAIN = Path.of("src/main/java/com/axcore/platform");

    private static final String READER_FILE = "source/repository/SourceTableReader.java";

    /** 텍스트 블록, 문자열, 문자 리터럴, 블록 주석, 줄 주석. 앞에서부터 먼저 나온 것을 고른다. */
    private static final Pattern LITERALS_AND_COMMENTS =
            Pattern.compile(
                    "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'"
                            + "|/\\*[\\s\\S]*?\\*/|//[^\\n]*");

    private static final Pattern USES_READER = Pattern.compile("\\bSourceTableReader\\b");

    /**
     * 원천 DB에 직접 접속하는 표시. 원천 접속 주소는 {@code SourceEndpoint.jdbcUrl()}에서만 나오므로 그 호출을 먼저 본다. 주소를 손으로
     * 만들거나 드라이버·데이터소스를 직접 다루는 코드도 본다. 플랫폼 DB는 Spring이 만든 데이터소스로만 접속한다.
     */
    private static final Pattern OPENS_SOURCE =
            Pattern.compile(
                    "\\.jdbcUrl\\(|jdbc:postgresql|\\bDriverManager\\w*"
                            + "|\\b(SimpleDriver|SingleConnection|PGSimple|PGPooling|PGConnectionPool)DataSource\\b"
                            + "|\\bDataSourceBuilder\\b|\\bHikari\\w*|\\borg\\.postgresql\\.|\\bjava\\.sql\\.Driver\\b");

    /** 쓰는 파일과 그 파일이 정책을 지키는 방법. */
    private static final Map<String, String> USERS =
            Map.of(
                    "source/service/SyncRunJob.java",
                    "전체 읽기(197). FullLoadPlanner가 금지 표를 막고 금지 컬럼을 뺀 계획만 읽는다",
                    "source/service/SourceSampleJob.java",
                    "원천 표본. 금지 표는 읽지 않고 금지·민감 컬럼은 빼고 읽는다(391)",
                    "check/service/CandidateCheckJob.java",
                    "후보 시험. 금지 표·컬럼이면 읽지 않고 FAILED, 민감 값은 비운다(400)",
                    "mapping/service/MappingPreviewJob.java",
                    "매핑 미리보기. MappingPreviewPlanner가 requireCollectable로 금지 표·컬럼을 거절한 계획만 읽는다",
                    "source/cdc/CdcStartReference.java",
                    "CDC 시작 기준(currentXmax). 업무 행을 읽지 않는다");

    /** 원천에 직접 접속하는 파일과 이유. */
    private static final Map<String, String> CONNECTORS =
            Map.of(
                    READER_FILE,
                    "업무 행 읽기의 유일한 진입점(위 USERS가 사용처를 고정)",
                    "source/repository/SourceCatalogReader.java",
                    "연결 검사. 시스템 카탈로그만 읽고 업무 행은 읽지 않는다",
                    "source/domain/SourceEndpoint.java",
                    "접속 주소(jdbc URL)만 만든다");

    @Test
    void onlyListedFilesUseSourceTableReader() throws IOException {
        Set<String> users = filesMatching(USES_READER);
        users.remove(READER_FILE);

        Set<String> unlisted = new TreeSet<>(users);
        unlisted.removeAll(USERS.keySet());
        assertThat(unlisted)
                .as(
                        "SourceTableReader를 쓰는 새 파일입니다. 원천 데이터 정책(SourceColumnPolicy)으로 금지 표·컬럼을 막고 민감 값을 다루는지 확인한 뒤, 그 방법과 함께 USERS에 올리세요")
                .isEmpty();

        Set<String> stale = new TreeSet<>(USERS.keySet());
        stale.removeAll(users);
        assertThat(stale).as("더는 SourceTableReader를 쓰지 않는 파일입니다. 목록에서 지우세요").isEmpty();
    }

    @Test
    void onlyListedFilesConnectToSourceDatabases() throws IOException {
        assertThat(filesMatching(OPENS_SOURCE))
                .as(
                        "원천에 직접 접속하는 파일 목록이 바뀌었습니다. 업무 행은 SourceTableReader로 읽고, 다른 이유면 CONNECTORS에 이유와 함께 올리세요")
                .containsExactlyInAnyOrderElementsOf(CONNECTORS.keySet());
    }

    @Test
    void patternFindsEveryUseOutsideComments() {
        assertThat(uses("// JDBC 경로(SourceTableReader)와 같이")).isFalse();
        assertThat(uses("/* SourceTableReader reader; */ int x;")).isFalse();
        assertThat(uses("private final SourceTableReader tableReader;")).isTrue();
        assertThat(uses("SourceTableReader.Result read;")).isTrue();
        assertThat(uses("ObjectProvider<SourceTableReader> readers;")).isTrue();
        assertThat(uses("context.getBean(SourceTableReader.class)")).isTrue();
        assertThat(uses("map(SourceTableReader::read)")).isTrue();
        assertThat(uses("import com.axcore.platform.source.repository.SourceTableReader.Table;"))
                .isTrue();
        assertThat(uses("private final SourceTableReaderCache cache;")).isFalse();

        for (String connect :
                List.of(
                        "DriverManager.getConnection(url)",
                        "new PGSimpleDataSource()",
                        "DataSourceBuilder.create().url(endpoint.jdbcUrl())",
                        "new HikariDataSource(config)",
                        "new org.postgresql.Driver().connect(url, properties)",
                        "new DriverManagerDataSource(endpoint.jdbcUrl(), user, password)",
                        "new SimpleDriverDataSource(driver, url)",
                        "new SingleConnectionDataSource(url, true)",
                        "String url = endpoint.jdbcUrl();",
                        "String h = \"http://x\"; DriverManager.getConnection(url);")) {
            assertThat(opens(connect)).as(connect).isTrue();
        }
        assertThat(opens("// DriverManager 대신")).isFalse();
        assertThat(opens("dataSourceService.findDataSource(id); DataSource source;")).isFalse();
        assertThat(uses("String p = \"src/*\"; SourceTableReader reader; // */")).isTrue();
        assertThat(uses("char c = '\"'; SourceTableReader reader;")).isTrue();
    }

    private static boolean opens(String source) {
        return OPENS_SOURCE.matcher(withoutComments(source)).find();
    }

    private static boolean uses(String source) {
        return USES_READER.matcher(withoutComments(source)).find();
    }

    /** 주석은 지우고, 리터럴은 남기되 안의 {@code /}를 바꿔 주석 표시로 읽히지 않게 한다(접속 주소 문자열은 찾아야 하므로 지우지 않는다). */
    private static String withoutComments(String source) {
        return LITERALS_AND_COMMENTS
                .matcher(source)
                .replaceAll(
                        match -> {
                            String token = match.group();
                            boolean comment = token.startsWith("//") || token.startsWith("/*");
                            return Matcher.quoteReplacement(
                                    comment ? " " : token.replace('/', '_'));
                        });
    }

    private static Set<String> filesMatching(Pattern pattern) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (pattern.matcher(withoutComments(source)).find()) {
                    found.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return found;
    }
}
