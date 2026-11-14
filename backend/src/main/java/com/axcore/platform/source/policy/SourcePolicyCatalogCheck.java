package com.axcore.platform.source.policy;

import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 때 원천 데이터 정책 항목을 원천 카탈로그(연결 검사가 채운 source_resource·source_column)와 견주어 경고한다(S15P31C106-405).
 * 기동을 멈추지는 않는다. 마지막 연결 검사에서 플랫폼 계정이 읽을 수 있던 표(is_readable)와 그 검사에서 읽힌 컬럼(표와 같은 last_seen_at)만
 * 보므로(원천 허용 목록, 395), 금지 항목은 여기 없어야 맞다. 권한이 빠진 표·컬럼은 카탈로그 행이 남아도 읽을 수 없는 것으로 본다.
 *
 * <ul>
 *   <li>민감 항목: 카탈로그에 그 컬럼이 없으면 경고한다. 이름이 틀렸거나 원천에서 바뀐 것이다.
 *   <li>금지 컬럼: 카탈로그에 그 표가 없으면 경고한다(표 이름 확인). 그 컬럼이 카탈로그에 보이면 원천 허용 목록이 적용되지 않은 것이라 경고한다.
 *   <li>금지 표({@code 스키마.표.*}): 카탈로그에 보이면 경고한다. 없으면 맞는 상태라 이름 오타는 여기서 찾지 못한다.
 * </ul>
 *
 * <p>카탈로그가 비어 있으면(원천 등록 전) 견주지 않는다. 읽을 수 있는 표가 하나도 없는 스키마의 항목도 견주지 않는다(그 원천을 아직 등록하지 않음). 같은 {@code
 * 스키마.표}가 여러 원천에 있으면 모두 합쳐 본다. 표·컬럼 이름은 대소문자를 보지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourcePolicyCatalogCheck {

    private static final String WHOLE_TABLE = "*";

    private final SourceColumnPolicy policy;
    private final SourceResourceRepository resources;
    private final SourceColumnRepository columns;

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        // 환경변수·외부 설정으로 목록 일부가 줄어도 기동은 막지 못한다. 배포 때 결정과 같은 목록인지 지문으로 확인할 수 있게 남긴다.
        log.info(
                "원천 데이터 정책: 금지 {}개, 민감 {}개, 지문 {}",
                policy.forbiddenEntries().size(),
                policy.restrictedEntries().size(),
                policy.fingerprint());
        try {
            List<String> findings = check();
            findings.forEach(finding -> log.warn("원천 데이터 정책 확인: {}", finding));
        } catch (RuntimeException e) {
            log.warn("원천 데이터 정책을 원천 카탈로그와 견주지 못했습니다: {}", e.toString());
        }
    }

    /** 경고 문장 목록. 카탈로그가 비어 있으면 빈 목록이다. */
    public List<String> check() {
        List<SourceResource> readable = resources.findByIsReadableTrue();
        if (readable.isEmpty()) {
            log.info("원천 카탈로그에 읽을 수 있는 표가 없어 원천 데이터 정책 항목을 견주지 않습니다.");
            return List.of();
        }
        Set<String> tables = new TreeSet<>();
        policy.forbiddenEntries().forEach(entry -> tables.add(table(entry)));
        policy.restrictedEntries().forEach(entry -> tables.add(table(entry)));
        Map<Long, SourceResource> byId = new HashMap<>();
        Map<String, Set<String>> catalog = new HashMap<>();
        Set<String> schemas = new TreeSet<>();
        for (SourceResource resource : readable) {
            String key = resource.getResourceKey().toLowerCase(Locale.ROOT);
            if (key.contains(".")) {
                schemas.add(key.substring(0, key.indexOf('.')));
            }
            if (tables.contains(key)) {
                byId.put(resource.getId(), resource);
                catalog.computeIfAbsent(key, ignored -> new TreeSet<>());
            }
        }
        if (!byId.isEmpty()) {
            // 권한만 빠진 컬럼은 is_present=true로 남는다. 마지막 연결 검사에서 읽힌 컬럼만 표와 같은 시각(last_seen_at)을 가진다.
            for (SourceColumn column :
                    columns.findBySourceResourceIdInAndIsPresentTrue(byId.keySet())) {
                SourceResource resource = byId.get(column.getSourceResourceId());
                if (column.getLastSeenAt().equals(resource.getLastSeenAt())) {
                    catalog.get(resource.getResourceKey().toLowerCase(Locale.ROOT))
                            .add(column.getColumnName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return compare(policy, catalog, schemas);
    }

    /**
     * 정책 항목과 카탈로그를 견준다.
     *
     * @param catalog 소문자 {@code 스키마.표} → 그 표에서 읽을 수 있는 소문자 컬럼 이름. 정책에 나온 표만 있으면 된다.
     * @param schemas 읽을 수 있는 표가 하나라도 있는 소문자 스키마. 여기 없는 스키마의 항목은 건너뛴다.
     */
    static List<String> compare(
            SourceColumnPolicy policy, Map<String, Set<String>> catalog, Set<String> schemas) {
        List<String> findings = new ArrayList<>();
        for (String entry : policy.forbiddenEntries()) {
            if (!schemas.contains(schema(entry))) {
                continue;
            }
            String table = table(entry);
            String column = column(entry);
            Set<String> readable = catalog.get(table);
            if (WHOLE_TABLE.equals(column)) {
                if (readable != null) {
                    findings.add("금지 표 " + table + "가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.");
                }
            } else if (readable == null) {
                findings.add("금지 항목 " + entry + "의 표 " + table + "가 카탈로그에 없습니다. 표 이름을 확인하세요.");
            } else if (readable.contains(column)) {
                findings.add("금지 컬럼 " + entry + "가 카탈로그에 보입니다. 원천 허용 목록이 적용됐는지 확인하세요.");
            }
        }
        for (String entry : policy.restrictedEntries()) {
            if (!schemas.contains(schema(entry))
                    || policy.gradeOf(table(entry), column(entry)) == SourceColumnGrade.FORBIDDEN) {
                continue;
            }
            Set<String> readable = catalog.get(table(entry));
            if (readable == null || !readable.contains(column(entry))) {
                findings.add("민감 항목 " + entry + "가 카탈로그에 없습니다. 표·컬럼 이름을 확인하세요.");
            }
        }
        return findings;
    }

    private static String schema(String entry) {
        return entry.substring(0, entry.indexOf('.'));
    }

    private static String table(String entry) {
        return entry.substring(0, entry.lastIndexOf('.'));
    }

    private static String column(String entry) {
        return entry.substring(entry.lastIndexOf('.') + 1);
    }
}
