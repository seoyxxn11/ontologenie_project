package com.axcore.platform.source.policy;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 원천 컬럼 등급을 판단하는 한 곳(S15P31C106-384·391). 매핑 등록·수정·승인 반영·적재 계획·원천 표본이 모두 이 판단을 쓴다. 정책은 {@link
 * SourceDataPolicyProperties} 하나가 기준이다.
 *
 * <ul>
 *   <li>표 전체 금지({@code 스키마.표.*})이면 그 표의 모든 컬럼이 금지다.
 *   <li>같은 컬럼이 두 목록에 있으면 금지가 이긴다.
 *   <li>목록에 없는 컬럼은 NORMAL이다. 서버가 강제할 것이 없다는 뜻이지 분류가 끝났다는 뜻은 아니다.
 * </ul>
 */
@Component
@EnableConfigurationProperties(SourceDataPolicyProperties.class)
public class SourceColumnPolicy {

    private static final String WHOLE_TABLE = "*";

    private final Set<String> forbidden;
    private final Set<String> restricted;

    /**
     * 항목 형식: {@code 스키마.표.컬럼} 또는 {@code 스키마.표.*}. PostgreSQL의 따옴표 없는 이름처럼 영문자·밑줄로 시작한다. 대소문자는 보지
     * 않는다.
     */
    private static final Pattern ENTRY =
            Pattern.compile("[a-z_][a-z0-9_$]*\\.[a-z_][a-z0-9_$]*\\.(\\*|[a-z_][a-z0-9_$]*)");

    /**
     * 민감 항목 형식: {@code 스키마.표.컬럼}만. 민감은 컬럼 단위로만 판단하므로({@link #gradeOf}) {@code 스키마.표.*}를 받으면 어떤 컬럼에도
     * 맞지 않아 조용히 열린다.
     */
    private static final Pattern COLUMN_ENTRY =
            Pattern.compile("[a-z_][a-z0-9_$]*\\.[a-z_][a-z0-9_$]*\\.[a-z_][a-z0-9_$]*");

    /**
     * 금지 목록이 비었거나 형식이 틀린 항목이 있으면 만들지 않는다(405). 금지 목록이 비면 민감 목록이 남아 있어도 금지 표·컬럼이 모두 NORMAL로 열리고,
     * {@code 스키마.표}처럼 {@code .*}를 빠뜨린 항목은 어떤 컬럼에도 맞지 않아 조용히 열린다. 그래서 기동을 멈추고, 금지 목록이 빈 정책은 {@code
     * allow-empty: true}로만 허용한다.
     */
    public SourceColumnPolicy(SourceDataPolicyProperties properties) {
        List<String> invalid = new ArrayList<>();
        invalid.addAll(malformed("forbidden", properties.forbidden(), ENTRY));
        invalid.addAll(malformed("restricted", properties.restricted(), COLUMN_ENTRY));
        if (!invalid.isEmpty()) {
            throw new IllegalStateException(
                    "axcore.source-data-policy 항목 형식이 틀렸습니다(금지는 스키마.표.컬럼 또는 스키마.표.*, 민감은 스키마.표.컬럼): "
                            + String.join(", ", invalid));
        }
        this.forbidden = normalized(properties.forbidden());
        this.restricted = normalized(properties.restricted());
        if (forbidden.isEmpty() && !properties.allowEmpty()) {
            throw new IllegalStateException(
                    "axcore.source-data-policy의 금지 목록(forbidden)이 없거나 비어 있습니다. 금지 원천 데이터가 모두 열리므로 기동하지 않습니다."
                            + " 정책 없이 띄워야 하면 axcore.source-data-policy.allow-empty=true로 명시하세요.");
        }
    }

    /** 표 전체 금지를 포함한 금지 항목(소문자, 이름순). 기동 때 원천 카탈로그와 견주는 데 쓴다. */
    public SortedSet<String> forbiddenEntries() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(forbidden));
    }

    /** 민감 항목(소문자, 이름순). 금지 목록에도 있는 항목은 금지가 이기지만 여기에는 그대로 남는다. */
    public SortedSet<String> restrictedEntries() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(restricted));
    }

    /** 표 전체가 금지인가. resourceKey는 {@code 스키마.표}다. */
    public boolean forbidsTable(String resourceKey) {
        return forbidden.contains(key(resourceKey, WHOLE_TABLE));
    }

    /** 이 표에 금지·민감 항목(표 전체 포함)이 하나라도 있는가. 컬럼 목록 없이 표 전체를 넘겨도 되는지 판단할 때 쓴다. */
    public boolean coversTable(String resourceKey) {
        String prefix = (resourceKey + ".").toLowerCase(Locale.ROOT);
        return forbidden.stream().anyMatch(entry -> entry.startsWith(prefix))
                || restricted.stream().anyMatch(entry -> entry.startsWith(prefix));
    }

    public SourceColumnGrade gradeOf(String resourceKey, String column) {
        if (forbidsTable(resourceKey) || forbidden.contains(key(resourceKey, column))) {
            return SourceColumnGrade.FORBIDDEN;
        }
        if (restricted.contains(key(resourceKey, column))) {
            return SourceColumnGrade.RESTRICTED;
        }
        return SourceColumnGrade.NORMAL;
    }

    /** 금지된 컬럼 이름(받은 순서). 표 전체가 금지이면 받은 컬럼 모두다. */
    public List<String> forbiddenColumns(String resourceKey, Collection<String> columns) {
        return columns.stream()
                .filter(column -> gradeOf(resourceKey, column) == SourceColumnGrade.FORBIDDEN)
                .toList();
    }

    /**
     * 표나 컬럼이 금지이면 매핑을 거절한다. 매핑 등록·수정과 승인 반영이 부른다.
     *
     * @param field 오류에 담을 요청 칸 이름
     */
    public void requireCollectable(String resourceKey, Collection<String> columns, String field) {
        if (forbidsTable(resourceKey)) {
            throw forbiddenError(field, resourceKey, List.of(WHOLE_TABLE));
        }
        List<String> denied = forbiddenColumns(resourceKey, columns);
        if (!denied.isEmpty()) {
            throw forbiddenError(field, resourceKey, denied);
        }
    }

    /**
     * 객체 매핑의 키 컬럼은 금지도 민감도 아니어야 한다(391). 키 값은 객체의 원천 키로 저장되고, 대표 속성이 없거나 가려지면 표시 이름으로도 쓰인다. 원천 키
     * 가림은 그 컬럼에 민감 속성이 매핑돼 있을 때만 동작하므로, 민감 컬럼을 키로 두면 값이 그대로 드러난다.
     */
    public void requireKeyColumns(String resourceKey, Collection<String> columns, String field) {
        requireCollectable(resourceKey, columns, field);
        List<String> restricted = restrictedColumns(resourceKey, columns);
        if (!restricted.isEmpty()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "원천 데이터 정책상 민감 컬럼은 키로 쓸 수 없습니다.",
                    Map.of(
                            "field",
                            field,
                            "resourceKey",
                            resourceKey,
                            "restrictedColumns",
                            restricted,
                            "reason",
                            "RESTRICTED_KEY"));
        }
    }

    /** 민감 컬럼 이름(받은 순서). */
    public List<String> restrictedColumns(String resourceKey, Collection<String> columns) {
        return columns.stream()
                .filter(column -> gradeOf(resourceKey, column) == SourceColumnGrade.RESTRICTED)
                .toList();
    }

    /** 정책 목록의 지문(SHA-256 앞 12자리). 민감 값 열람 기록(394)에 정책 버전으로 남긴다. 같은 목록이면 순서·대소문자와 관계없이 같다. */
    public String fingerprint() {
        String text =
                "forbidden="
                        + String.join(",", new TreeSet<>(forbidden))
                        + ";restricted="
                        + String.join(",", new TreeSet<>(restricted));
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }

    private static BusinessException forbiddenError(
            String field, String resourceKey, List<String> columns) {
        return new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                "원천 데이터 정책상 들이지 않는 표·컬럼이라 매핑할 수 없습니다.",
                Map.of("field", field, "resourceKey", resourceKey, "forbiddenColumns", columns));
    }

    private static String key(String resourceKey, String column) {
        return (resourceKey + "." + column).toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalized(List<String> entries) {
        return entries.stream()
                .map(String::strip)
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> malformed(String list, List<String> entries, Pattern format) {
        return entries.stream()
                .filter(
                        entry ->
                                entry == null
                                        || !format.matcher(entry.strip().toLowerCase(Locale.ROOT))
                                                .matches())
                .map(entry -> list + "[" + (entry == null ? "" : entry.strip()) + "]")
                .toList();
    }
}
