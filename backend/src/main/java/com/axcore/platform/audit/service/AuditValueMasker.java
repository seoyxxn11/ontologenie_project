package com.axcore.platform.audit.service;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditEntityChangeResponse;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Kind;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Path;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Section;
import com.axcore.platform.auth.service.DataAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 실행 기록에서 보는 사람에게 보여 줄 값만 남긴다(API 091 결과, 092·097·098, 재배정 계획 093~095, S15P31C106-205·390·404).
 * 목록(096·080)은 입력값과 변경 내역을 주지 않아 가릴 것이 없다(S15P31C106-374).
 *
 * <p>허용 목록이다. 기록을 남기는 쪽이 {@link RecordedPropertyPaths}로 알린 자리만 보여 준다.
 *
 * <ul>
 *   <li>속성 자리(PROPERTY)는 보는 사람이 그 유형을 볼 수 없거나 민감 값을 볼 수 없으면 가리고, 코드를 restrictedPropertyCodes에 담는다.
 *       077 객체 상세와 같은 방식이다. 볼 수 있는 민감 값이면 코드를 revealedPropertyCodes에 담는다(394 열람 기록).
 *   <li>속성이 아닌 자리(PLAIN)는 그대로 둔다. PLAIN 안에 알린 속성 자리는 위 규칙으로 가린다.
 *   <li>알리지 않은 Handler의 입력·결과, 알리지 않은 표의 변경, 알린 기록 안의 알리지 않은 자리는 값을 가린다. 어느 속성인지 모르므로 코드는 담지 않는다.
 *   <li>Handler 없이 기록만 남기는 관리 Action(action_mode RECORD)의 입력·결과는 정의·관리 요청이라 가리지 않는다. 그 변경 표는 알려야
 *       보인다.
 * </ul>
 *
 * <p>키는 남기고 값만 null로 바꾼다. 원래 값은 바꾸지 않는다.
 */
@Component
public class AuditValueMasker {

    private static final String EACH = "*";

    /** 차단 기록(BLOCKED)의 result에 남기는 차단 주체 키(404). 응답에서는 뺀다. */
    public static final String BLOCKED_BY = "blockedBy";

    /** 실행기가 막은 경우. 문구는 실행기의 고정 문구라 객체 값이 없다. */
    public static final String BLOCKED_BY_GATE = "GATE";

    /** Handler가 업무 조건으로 막은 경우. 문구에 객체 이름·일반 속성 값이 들어갈 수 있고 민감 값은 없다. */
    public static final String BLOCKED_BY_HANDLER = "HANDLER";

    /** 보는 사람에게 차단 이유를 보여 줄 수 없을 때 대신 주는 문구. */
    public static final String HIDDEN_BLOCKED_REASON = "차단 이유에 볼 수 없는 객체 정보가 있을 수 있어 가렸습니다.";

    private final Map<String, List<Path>> inputPaths;
    private final Map<String, List<Path>> resultPaths;
    private final Map<String, List<Path>> changePaths;
    private final Map<String, Set<String>> blockedReasonTypes;
    private final ActionDefinitionRepository definitions;

    AuditValueMasker(List<RecordedPropertyPaths> sources, ActionDefinitionRepository definitions) {
        List<Path> paths =
                sources.stream()
                        .flatMap(source -> source.recordedPropertyPaths().stream())
                        .distinct()
                        .toList();
        requireOneKindPerSlot(paths);
        requireNothingBelowProperty(paths);
        this.inputPaths = byOwner(paths, Section.INPUT);
        this.resultPaths = byOwner(paths, Section.RESULT);
        this.changePaths = byOwner(paths, Section.CHANGE);
        Map<String, Set<String>> types = new HashMap<>();
        sources.forEach(
                source ->
                        source.blockedReasonObjectTypes()
                                .forEach(
                                        (handlerKey, codes) ->
                                                types.computeIfAbsent(
                                                                handlerKey, key -> new TreeSet<>())
                                                        .addAll(codes)));
        this.blockedReasonTypes = Map.copyOf(types);
        this.definitions = definitions;
    }

    /**
     * 차단 이유를 보는 사람에 맞춰 돌려준다(091·092·097, 404). 문구는 저장된 한 줄이라 자리별로 가릴 수 없으므로 통째로 보여 주거나 {@link
     * #HIDDEN_BLOCKED_REASON}으로 바꾼다.
     *
     * <ul>
     *   <li>관리 Action(Handler 없음), 정의가 없는 기록, 실행기가 막은 기록(GATE)은 그대로다.
     *   <li>Handler가 막은 기록(HANDLER)은 그 Handler가 알린 유형({@link
     *       RecordedPropertyPaths#blockedReasonObjectTypes})을 모두 볼 수 있어야 보인다. 문구에 그 유형의 객체 이름·일반 속성
     *       값이 들어가기 때문이다. 유형을 알리지 않은 Handler면 가린다.
     *   <li>차단 주체가 없는 기록은 404 이전 기록이다. 그때는 사람이 실행하면 민감 값도 문구에 들어갔으므로, 위 유형의 민감 값까지 볼 수 있어야 보인다.
     * </ul>
     */
    public String maskBlockedReason(
            Long actionDefinitionId,
            ExecutionStatus status,
            String blockedReason,
            Map<String, Object> result,
            DataAccess access) {
        if (status != ExecutionStatus.BLOCKED
                || blockedReason == null
                || actionDefinitionId == null) {
            return blockedReason;
        }
        Optional<ActionDefinition> definition = definitions.findById(actionDefinitionId);
        if (definition.isPresent() && definition.get().getHandlerKey() == null) {
            return blockedReason;
        }
        Object blockedBy = result == null ? null : result.get(BLOCKED_BY);
        if (BLOCKED_BY_GATE.equals(blockedBy)) {
            return blockedReason;
        }
        Set<String> types =
                definition
                        .map(ActionDefinition::getHandlerKey)
                        .map(key -> blockedReasonTypes.getOrDefault(key, Set.of()))
                        .orElse(Set.of());
        boolean legacy = !BLOCKED_BY_HANDLER.equals(blockedBy);
        boolean visible =
                !types.isEmpty()
                        && types.stream()
                                .allMatch(
                                        type ->
                                                access.canView(type)
                                                        && (!legacy
                                                                || access.canViewRestricted(type)));
        return visible ? blockedReason : HIDDEN_BLOCKED_REASON;
    }

    /**
     * 실행 하나의 입력값을 가린다(092·097). 실행한 정의의 Handler가 알린 자리만 보여 준다. 정의가 없는 기록(Agent 조회 등)과 관리 Action의
     * 입력은 그대로 둔다. 정의를 찾을 수 없으면 모르는 Handler로 보고 가린다.
     */
    public Masked<Map<String, Object>> maskInput(
            Long actionDefinitionId, Map<String, Object> input, DataAccess access) {
        return maskHandlerRecord(inputPaths, actionDefinitionId, input, access);
    }

    /**
     * 실행 하나의 결과를 가린다(091·092·097). 성공한 실행의 결과만 Handler가 만든 값이므로 Handler가 알린 자리만 보여 준다. 실패의 결과는 실행기가
     * 남긴 고정 문구라 그대로 둔다. 정의가 없는 기록과 관리 Action은 입력과 같게 그대로 둔다. 차단 기록의 차단 주체({@link #BLOCKED_BY})는 내부
     * 표시라 뺀다.
     */
    public Masked<Map<String, Object>> maskResult(
            Long actionDefinitionId,
            ExecutionStatus status,
            Map<String, Object> result,
            DataAccess access) {
        if (status != ExecutionStatus.SUCCEEDED) {
            if (result == null || !result.containsKey(BLOCKED_BY)) {
                return new Masked<>(result, List.of(), List.of());
            }
            Map<String, Object> rest = new LinkedHashMap<>(result);
            rest.remove(BLOCKED_BY);
            return new Masked<>(rest.isEmpty() ? null : rest, List.of(), List.of());
        }
        return maskHandlerRecord(resultPaths, actionDefinitionId, result, access);
    }

    private Masked<Map<String, Object>> maskHandlerRecord(
            Map<String, List<Path>> section,
            Long actionDefinitionId,
            Map<String, Object> data,
            DataAccess access) {
        if (data == null || actionDefinitionId == null) {
            return new Masked<>(data, List.of(), List.of());
        }
        Optional<ActionDefinition> definition = definitions.findById(actionDefinitionId);
        if (definition.isPresent() && definition.get().getHandlerKey() == null) {
            return new Masked<>(data, List.of(), List.of());
        }
        String handlerKey = definition.map(ActionDefinition::getHandlerKey).orElse(null);
        Walk walk = new Walk(access);
        return walk.result(walk.record(data, paths(section, handlerKey)));
    }

    /** 표 한 행을 그 표에 알린 자리로 가린다(384·390). 재배정 계획 항목(093~095)처럼 저장된 행을 돌려줄 때 쓴다. */
    public Masked<Map<String, Object>> maskRow(
            String tableName, Map<String, Object> row, DataAccess access) {
        Walk walk = new Walk(access);
        Map<String, Object> value = walk.record(row, paths(changePaths, tableName));
        return walk.result(value);
    }

    /** 실행 하나의 변경 내역을 가린다(092·098). */
    public Masked<List<AuditEntityChangeResponse>> maskChanges(
            List<AuditEntityChangeResponse> changes, DataAccess access) {
        Walk walk = new Walk(access);
        List<AuditEntityChangeResponse> value =
                changes.stream()
                        .map(
                                change -> {
                                    List<Path> paths = paths(changePaths, change.tableName());
                                    return new AuditEntityChangeResponse(
                                            change.tableName(),
                                            change.rowKey(),
                                            change.changeKind(),
                                            walk.record(change.beforeData(), paths),
                                            walk.record(change.afterData(), paths));
                                })
                        .toList();
        return walk.result(value);
    }

    /** 한 번의 가림. 가린 코드와 보여 준 민감 코드를 모은다. */
    private static final class Walk {

        private final DataAccess access;
        private final Set<String> masked = new TreeSet<>();
        private final Set<String> revealed = new TreeSet<>();

        Walk(DataAccess access) {
            this.access = access;
        }

        <T> Masked<T> result(T value) {
            return new Masked<>(value, List.copyOf(masked), List.copyOf(revealed));
        }

        /** 기록 하나. 키가 빈 PLAIN이 있으면 기록 전체가 속성 값이 아닌 자리다. */
        @SuppressWarnings("unchecked")
        Map<String, Object> record(Map<String, Object> data, List<Path> paths) {
            if (data == null) {
                return null;
            }
            boolean plain = paths.stream().anyMatch(path -> path.keys().isEmpty());
            List<Path> slots = paths.stream().filter(path -> !path.keys().isEmpty()).toList();
            return (Map<String, Object>) node(data, slots, 0, plain);
        }

        /** paths는 지금 노드까지 키가 맞은 자리이고, depth는 다음에 볼 키의 위치다. */
        private Object node(Object node, List<Path> paths, int depth, boolean plain) {
            if (node instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach(
                        (key, value) ->
                                copy.put(
                                        key,
                                        slot(
                                                value,
                                                matching(paths, depth, String.valueOf(key)),
                                                depth + 1,
                                                plain)));
                return copy;
            }
            if (node instanceof List<?> list) {
                List<Path> each = matching(paths, depth, EACH);
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(item -> copy.add(slot(item, each, depth + 1, plain)));
                return copy;
            }
            // 알린 자리가 더 깊은데 값이 맵·목록이 아니다. 모양이 다르므로 PLAIN 안이 아니면 가린다.
            return plain ? node : null;
        }

        private Object slot(Object value, List<Path> paths, int depth, boolean plain) {
            Path here =
                    paths.stream()
                            .filter(path -> path.keys().size() == depth)
                            .findFirst()
                            .orElse(null);
            if (here != null && here.kind() == Kind.PROPERTY) {
                return property(value, here);
            }
            boolean plainHere = plain || here != null;
            List<Path> deeper = paths.stream().filter(path -> path.keys().size() > depth).toList();
            if (value == null) {
                return null;
            }
            if (!deeper.isEmpty()) {
                return node(value, deeper, depth, plainHere);
            }
            return plainHere ? value : null;
        }

        /** 유형을 볼 수 없거나(모르는 유형 포함) 민감 값을 볼 수 없으면 가린다. 키가 있으면 값이 비어도 가린 코드로 담는다. */
        private Object property(Object value, Path path) {
            String type = path.objectTypeCode();
            String code = path.propertyCode();
            if (!access.canView(type) || access.hidesValue(type, code)) {
                masked.add(code);
                return null;
            }
            if (value != null && access.revealsValue(type, code)) {
                revealed.add(code);
            }
            return value;
        }

        private static List<Path> matching(List<Path> paths, int depth, String key) {
            if (paths.isEmpty()) {
                return paths;
            }
            return paths.stream()
                    .filter(path -> path.keys().size() > depth)
                    .filter(path -> path.keys().get(depth).equals(key))
                    .toList();
        }
    }

    private static List<Path> paths(Map<String, List<Path>> paths, String owner) {
        return owner == null ? List.of() : paths.getOrDefault(owner, List.of());
    }

    private static Map<String, List<Path>> byOwner(List<Path> paths, Section section) {
        return paths.stream()
                .filter(path -> path.section() == section)
                .collect(Collectors.groupingBy(Path::owner));
    }

    /** 같은 자리를 다른 종류(또는 다른 속성)로 두 번 알리면 어느 쪽으로 가릴지 모르므로 시작하지 않는다. */
    private static void requireOneKindPerSlot(List<Path> paths) {
        Map<List<Object>, Path> seen = new HashMap<>();
        for (Path path : paths) {
            Path before =
                    seen.putIfAbsent(List.of(path.section(), path.owner(), path.keys()), path);
            if (before != null) {
                throw new IllegalStateException(
                        "같은 기록 자리를 두 번 알렸습니다: %s %s %s"
                                .formatted(path.section(), path.owner(), path.keys()));
            }
        }
    }

    /** PROPERTY 자리가 보이면 그 아래 값은 통째로 나가므로, 그 아래에 다른 자리를 알리면 그 자리는 적용되지 않는다. 실수를 막으려고 시작하지 않는다. */
    private static void requireNothingBelowProperty(List<Path> paths) {
        for (Path property : paths) {
            if (property.kind() != Kind.PROPERTY) {
                continue;
            }
            List<String> prefix = property.keys();
            for (Path other : paths) {
                if (other.section() == property.section()
                        && other.owner().equals(property.owner())
                        && other.keys().size() > prefix.size()
                        && other.keys().subList(0, prefix.size()).equals(prefix)) {
                    throw new IllegalStateException(
                            "속성 자리 아래에 다른 자리를 알렸습니다: %s %s %s"
                                    .formatted(other.section(), other.owner(), other.keys()));
                }
            }
        }
    }

    /**
     * 가린 값과 가린 속성 코드(이름순), 가리지 않고 보여 준 민감 속성 코드(이름순). 가린 코드는 민감 속성이 대부분이지만, 보는 사람이 볼 수 없는 유형의 속성이면
     * 민감이 아니어도 들어간다(404). 알리지 않아 가린 자리는 어느 속성인지 몰라 들어가지 않는다.
     */
    public record Masked<T>(
            T value, List<String> restrictedPropertyCodes, List<String> revealedPropertyCodes) {}
}
