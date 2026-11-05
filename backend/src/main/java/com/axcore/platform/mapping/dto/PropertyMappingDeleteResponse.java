package com.axcore.platform.mapping.dto;

import com.axcore.platform.mapping.domain.ReloadReason;
import com.fasterxml.jackson.annotation.JsonInclude;

/** API 101. 소속 객체 매핑이 켜져 있으면 PROPERTY_MAPPING_DELETED로 테이블을 처음부터 다시 읽는다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PropertyMappingDeleteResponse(
        Long id,
        boolean deleted,
        Long sourceMappingId,
        Long sourceResourceId,
        boolean reloadRequired,
        ReloadReason reloadReason) {

    public static PropertyMappingDeleteResponse of(
            Long id, Long sourceMappingId, Long sourceResourceId, ReloadReason reloadReason) {
        return new PropertyMappingDeleteResponse(
                id, true, sourceMappingId, sourceResourceId, reloadReason != null, reloadReason);
    }
}
