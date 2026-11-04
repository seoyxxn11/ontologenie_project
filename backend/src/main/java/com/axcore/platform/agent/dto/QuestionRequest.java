package com.axcore.platform.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QuestionRequest(
        @NotBlank @Size(max = 2000) String content,
        List<@NotNull @Min(1) Long> referenceObjectIds,
        Map<String, Object> context) {

    /** USER 메시지 context에 보존할 값: 화면이 보낸 조건과 참고 객체(085 처리 규칙). */
    public Map<String, Object> messageContext() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (context != null) {
            merged.putAll(context);
        }
        if (referenceObjectIds != null) {
            merged.put("referenceObjectIds", referenceObjectIds);
        }
        return merged;
    }
}
