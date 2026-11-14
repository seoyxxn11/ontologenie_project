package com.axcore.platform.check.dto;

import java.util.List;

public record ReverificationComparisonResponse(
        long comparedToRunId, Coverage coverage, boolean hasBaselinePassEvidence, List<Row> rows) {

    public enum Coverage {
        PARTIAL,
        UNAVAILABLE
    }

    public enum Category {
        RESOLVED,
        REMAINING,
        NEW
    }

    /**
     * 비교 근거 하나. rawValue·transformedValue는 그 판정에 쓴 규칙 개정본과 보는 사람의 조회 범위로 가린 값이고, 가린 칸 이름은
     * restrictedFields에 있다(403).
     */
    public record Evidence(
            long linkMappingId,
            String classification,
            String rawValue,
            String transformedValue,
            String reason,
            List<String> restrictedFields) {

        public Evidence {
            restrictedFields = restrictedFields == null ? List.of() : List.copyOf(restrictedFields);
        }

        /** 저장된 근거. 가리기 전이다. */
        public Evidence(
                long linkMappingId,
                String classification,
                String rawValue,
                String transformedValue,
                String reason) {
            this(linkMappingId, classification, rawValue, transformedValue, reason, List.of());
        }
    }

    public record Row(
            Category category,
            String ruleCode,
            long objectInstanceId,
            Evidence before,
            Evidence after) {}
}
