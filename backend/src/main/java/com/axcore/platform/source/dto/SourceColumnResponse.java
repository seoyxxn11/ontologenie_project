package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.SourceColumn;
import com.fasterxml.jackson.annotation.JsonInclude;

/** API 015 한 건. isPresent=false는 원천에서 사라진 컬럼이다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceColumnResponse(
        Long id,
        Long sourceResourceId,
        String columnName,
        String sourceDataType,
        boolean isNullable,
        boolean isKeyPart,
        boolean isPresent,
        int ordinalPosition,
        String sourceComment) {

    public static SourceColumnResponse from(SourceColumn column) {
        return new SourceColumnResponse(
                column.getId(),
                column.getSourceResourceId(),
                column.getColumnName(),
                column.getSourceDataType(),
                column.isNullable(),
                column.isKeyPart(),
                column.isPresent(),
                column.getOrdinalPosition(),
                column.getSourceComment());
    }
}
