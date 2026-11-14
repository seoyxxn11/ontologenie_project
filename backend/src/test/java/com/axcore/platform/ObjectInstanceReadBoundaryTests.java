package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 객체(object_instance)를 읽는 SQL이 정해진 곳에만 있는지 확인한다(S15P31C106-384). 조회 범위({@code DataAccess})는 읽는 쪽이
 * 직접 적용해야 동작하므로, 새 코드가 object_instance를 직접 읽으면 범위를 빼먹은 채 값이 나갈 수 있다. 그래서 읽는 파일을 아래 목록으로 고정하고, 목록에
 * 없는 파일이 읽거나 목록의 파일이 더는 읽지 않으면 실패한다. 사용자 요청 경로는 {@code ScopedObjectReader}로 읽는다.
 *
 * <p>한계: 문자열을 이어 붙여 만든 SQL(예: 표 이름을 변수로 받는 참조 검사)은 찾지 못한다. 그런 곳은 값이 아니라 존재 여부만 읽어야 한다.
 */
class ObjectInstanceReadBoundaryTests {

    private static final Path MAIN = Path.of("src/main/java/com/axcore/platform");

    private static final Pattern READS_OBJECTS =
            Pattern.compile("\\b(from|join)\\s+object_instance\\b", Pattern.CASE_INSENSITIVE);

    /** 범위를 적용해 사용자에게 돌려주는 곳. */
    private static final Map<String, String> SCOPED =
            Map.of(
                    "ontology/repository/ScopedObjectReader.java", "공통 읽기 진입점(384)",
                    "ontology/repository/ObjectQueryRepository.java",
                            "객체 조회 076~078. ObjectQueryService가 DataAccess로 거르고 가린다",
                    "ontology/repository/PropertyValueSampleRepository.java",
                            "Agent 조건 값 종류. PropertyValueSampleService가 볼 수 있는 유형·속성만 넘긴다",
                    "audit/repository/AuditExecutionQueryRepository.java",
                            "실행 이력의 대상 표시 이름. 볼 수 있는 유형만, ObjectDisplaySql로 가린다(390)");

    /**
     * 사용자 요청 없이 돌거나 객체 값을 바로 응답에 담지 않는 곳(쓰기, 내부 작업, ID·유형·건수만 읽는 확인). 검사 배치처럼 읽은 값을 사본으로 저장해 뒤에
     * 응답으로 내보내는 곳은 그 사본을 돌려주는 쪽이 범위를 다시 적용한다. 설명에 그 위치를 적는다.
     */
    private static final Map<String, String> INTERNAL =
            Map.ofEntries(
                    Map.entry(
                            "action/repository/ActionReferenceRepository.java", "범위 검사용 객체 유형 ID"),
                    Map.entry("agent/repository/DataAsOfRepository.java", "결과 객체의 원천 수집 상태(값 없음)"),
                    Map.entry(
                            "check/repository/CheckJudgmentReader.java",
                            "정합성 검사 배치(수집 실패·삭제 여부만, 값 없음)"),
                    Map.entry(
                            "check/repository/CheckObjectReader.java",
                            "정합성 검사 배치. 읽은 값의 사본이 문제(065~068)·검사 결과 표본(062)·재검증 기록(070)·재검증 비교로"
                                    + " 나간다. 응답 때 StoredCheckValues가 규칙의 지금 정의와 보는 사람의 범위로 가린다(403)"),
                    Map.entry(
                            "mapping/repository/ObjectSourceMappingRepository.java",
                            "매핑을 쓰는 객체가 있는지"),
                    Map.entry(
                            "ontology/repository/ConfirmedLinkRepository.java",
                            "확정 연결 저장 전 검증(값을 응답에 내보내지 않음)"),
                    Map.entry("ontology/repository/ObjectSyncRepository.java", "수집 반영(쓰기)"),
                    Map.entry(
                            "ontology/repository/PropertyValueRepository.java",
                            "속성 정의 변경 전 값 형식 검사(건수·ID만)"),
                    Map.entry("ontology/repository/RuleLinkRepository.java", "규칙 연결 재계산"));

    /**
     * 공통 읽기 계층으로 옮겨야 하는 곳. 옮기면 목록에서 지운다. 지우지 않으면 "더는 읽지 않는 파일"로 실패한다. 390에서 재배정 객체 읽기와 감사 대상 표시 이름을
     * 옮겨 지금은 비어 있다.
     */
    private static final Map<String, String> TO_MIGRATE = Map.of();

    @Test
    void onlyListedFilesReadObjectInstance() throws IOException {
        Set<String> readers = readersOfObjectInstance();
        Set<String> listed = new TreeSet<>();
        listed.addAll(SCOPED.keySet());
        listed.addAll(INTERNAL.keySet());
        listed.addAll(TO_MIGRATE.keySet());

        Set<String> unlisted = new TreeSet<>(readers);
        unlisted.removeAll(listed);
        assertThat(unlisted)
                .as(
                        "object_instance를 직접 읽는 새 파일입니다. 사용자 요청 경로면 ScopedObjectReader로 읽고, 내부 작업이면 이유와 함께 INTERNAL에 올리세요")
                .isEmpty();

        Set<String> stale = new TreeSet<>(listed);
        stale.removeAll(readers);
        assertThat(stale).as("더는 object_instance를 읽지 않는 파일입니다. 목록에서 지우세요").isEmpty();
    }

    private static Set<String> readersOfObjectInstance() throws IOException {
        Set<String> readers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (READS_OBJECTS.matcher(source).find()) {
                    readers.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return readers;
    }
}
