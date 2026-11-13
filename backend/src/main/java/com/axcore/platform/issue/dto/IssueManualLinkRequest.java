package com.axcore.platform.issue.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/** API 058 요청. 문제 상세에서 조회한 버전과 관리자가 고른 연결 대상이다. */
public record IssueManualLinkRequest(
        @NotNull @Min(1) @JsonDeserialize(using = IntegerValueDeserializer.class)
                Long targetObjectId,
        @NotBlank @Size(max = 1000) String reason,
        @NotNull @Min(0) @JsonDeserialize(using = IntegerValueDeserializer.class) Long rowVersion) {

    public static class IntegerValueDeserializer extends StdDeserializer<Long> {
        public IntegerValueDeserializer() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "정수여야 합니다.");
            }
            return parser.getLongValue();
        }
    }
}
