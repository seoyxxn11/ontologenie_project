package com.axcore.platform.review.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

public record AiProposalRequest(
        @NotNull
                @Pattern(
                        regexp =
                                "OBJECT_TYPE|PROPERTY|LINK_TYPE|OBJECT_MAPPING|PROPERTY_MAPPING|LINK_RULE|DOCUMENT_LINK")
                String itemKind,
        @NotNull Map<String, Object> payload,
        Map<String, Object> evidence,
        @DecimalMin("0") @DecimalMax("1") BigDecimal aiConfidence,
        @Valid List<@NotNull Option> options) {
    public AiProposalRequest {
        evidence = evidence == null ? Map.of() : evidence;
        options = options == null ? List.of() : options;
    }

    public record Option(
            @NotNull @Positive @JsonDeserialize(using = OptionNoDeserializer.class)
                    Integer optionNo,
            @NotNull Map<String, Object> payload,
            Map<String, Object> evidence,
            @DecimalMin("0") @DecimalMax("1") BigDecimal aiConfidence) {
        public Option {
            evidence = evidence == null ? Map.of() : evidence;
        }
    }

    public static class OptionNoDeserializer extends StdDeserializer<Integer> {
        public OptionNoDeserializer() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "선택지 번호는 정수여야 합니다.");
            }
            return parser.getIntValue();
        }
    }
}
