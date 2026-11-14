package com.axcore.platform.audit.dto;

import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 실행 하나의 내용 (API 097). 실행자·동작·대상 이름과 변경 내역은 넣지 않는다(목록 096·080과 098이 준다). failureReason은 FAILED일 때
 * result의 errorMessage, 없거나 비어 있으면 reason을 준다. 둘 다 없거나 비어 있으면 null이다. restrictedPropertyCodes는
 * input·result에서 값을 가린 속성 코드다(404).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditExecutionDetailResponse(
        Long id,
        ExecutionStatus status,
        Map<String, Object> input,
        Map<String, Object> result,
        String blockedReason,
        String failureReason,
        List<String> restrictedPropertyCodes) {

    private static final String ERROR_MESSAGE = "errorMessage";
    private static final String REASON = "reason";

    public static AuditExecutionDetailResponse of(
            ActionExecution execution,
            Map<String, Object> input,
            Map<String, Object> maskedResult,
            String maskedBlockedReason,
            List<String> restrictedPropertyCodes) {
        Map<String, Object> result = orEmpty(maskedResult);
        return new AuditExecutionDetailResponse(
                execution.getId(),
                execution.getStatus(),
                orEmpty(input),
                result,
                maskedBlockedReason,
                execution.getStatus() == ExecutionStatus.FAILED ? failureReason(result) : null,
                restrictedPropertyCodes);
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private static String failureReason(Map<String, Object> result) {
        String reason = textOf(result.get(ERROR_MESSAGE));
        return reason != null ? reason : textOf(result.get(REASON));
    }

    /** 비어 있거나 공백뿐인 값은 없는 값으로 본다. */
    private static String textOf(Object value) {
        String text = Objects.toString(value, null);
        return text == null || text.isBlank() ? null : text;
    }
}
