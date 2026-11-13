package com.axcore.platform.audit.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.policy.SourceColumnPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가리지 않은 민감(RESTRICTED) 값을 사람에게 내보낸 응답을 action_execution(RESTRICTED_VIEW)에 남긴다(384·394). 개인정보 처리
 * 시스템의 접속 기록이다.
 *
 * <ul>
 *   <li>응답을 만드는 서비스가 부른다. Action 검증처럼 사람에게 값을 내보내지 않는 내부 읽기는 부르지 않는다.
 *   <li>값 원문은 남기지 않는다. 대상(객체·실행 기록·재배정 계획), 필드 코드, 경로, 정책 버전만 남긴다. 역할은 실행 기록의 역할 스냅샷에 남는다.
 *   <li>보여 준 민감 값이 없으면(가렸거나 값이 비었으면) 남기지 않는다.
 *   <li>기록이 실패하면 응답도 실패한다. 접속 기록 없이 민감 값을 내보내지 않는다. 응답 트랜잭션과 따로 커밋한다.
 *   <li>이 기록은 ADMIN이 096에서 eventKind=RESTRICTED_VIEW로 골라 볼 때만 보인다. 다른 목록·상세·080·092·Agent에는 나오지
 *       않는다.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RestrictedViewRecorder {

    private final AuditRecorder audits;
    private final SourceColumnPolicy sourceColumnPolicy;

    /**
     * @param view 응답한 경로 이름(예: OBJECT_DETAIL)
     * @param seen 대상마다 보여 준 민감 속성 코드. 코드가 없는 대상은 뺀다.
     */
    public void record(LoginAccount viewer, String view, List<Seen> seen) {
        List<Seen> shown = seen.stream().filter(item -> !item.fields().isEmpty()).toList();
        if (shown.isEmpty()) {
            return;
        }
        Seen only = shown.size() == 1 ? shown.getFirst() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("view", view);
        result.put("seen", shown.stream().map(Seen::toMap).toList());
        result.put("policyVersion", sourceColumnPolicy.fingerprint());
        audits.recordSeparately(
                AuditEntry.builder()
                        .eventKind(EventKind.RESTRICTED_VIEW)
                        .actor(Actor.user(viewer))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .targetType(only == null ? null : only.targetType())
                        .targetId(only == null ? null : only.targetId())
                        .input(Map.of("view", view))
                        .result(result)
                        .build());
    }

    /** 보여 준 대상 하나와 그 대상에서 보여 준 민감 속성 코드. */
    public record Seen(String targetType, long targetId, List<String> fields) {

        public Seen {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public static Seen object(long objectId, List<String> fields) {
            return new Seen("object_instance", objectId, fields);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("targetType", targetType);
            map.put("targetId", targetId);
            map.put("fields", fields);
            return map;
        }
    }
}
