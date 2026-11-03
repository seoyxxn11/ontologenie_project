package com.axcore.platform.analysis.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

public record AnalysisCreateRequest(
        @NotNull @Pattern(regexp = "INITIAL|REANALYSIS") String runKind,
        @NotEmpty @Valid List<@NotNull Target> targets,
        Map<String, Object> analysisRange,
        @Positive @JsonDeserialize(using = IdDeserializer.class) Long basedOnSyncRunId) {
    public AnalysisCreateRequest {
        analysisRange = analysisRange == null ? Map.of() : analysisRange;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("지원하지 않는 분석 요청 필드입니다.");
    }

    public record Target(
            @NotNull @Positive @JsonDeserialize(using = IdDeserializer.class) Long sourceResourceId,
            @Size(min = 1) List<@NotBlank String> columns) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("지원하지 않는 분석 대상 필드입니다.");
        }
    }

    public static class IdDeserializer extends StdDeserializer<Long> {
        public IdDeserializer() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "ID는 정수여야 합니다.");
            }
            return parser.getLongValue();
        }
    }
}
