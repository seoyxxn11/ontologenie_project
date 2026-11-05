package com.axcore.platform.mapping.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/** 매핑의 변환 단계 하나 {@code {"op": 함수 코드, "arg": 인자}} (037~039). 인자가 없는 함수도 arg를 null로 내보낸다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransformStepPayload(String op, Object arg) {

    public static TransformStepPayload from(Map<String, Object> stored) {
        return new TransformStepPayload((String) stored.get("op"), stored.get("arg"));
    }

    /** 저장 형식. arg 키를 늘 두어 저장된 JSON과 응답 모양을 같게 한다. */
    public Map<String, Object> toStored() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("op", op);
        stored.put("arg", arg);
        return stored;
    }
}
