package com.axcore.platform.source.policy;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    public SourceColumnPolicy(SourceDataPolicyProperties properties) {
        this.forbidden = normalized(properties.forbidden());
        this.restricted = normalized(properties.restricted());
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
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
