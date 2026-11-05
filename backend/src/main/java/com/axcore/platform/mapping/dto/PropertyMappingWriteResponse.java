package com.axcore.platform.mapping.dto;

import com.axcore.platform.mapping.domain.ReloadReason;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * API 038·039. 이번 변경 때문에 다음 수집에서 테이블을 처음부터 다시 읽는지(reloadRequired)와 그 이유를 더한다. 이유는
 * PROPERTY_MAPPING_ADDED(등록) 또는 PROPERTY_MAPPING_CHANGED(변경)다. 소속 객체 매핑이 멈춰 있거나 바뀐 값이 없으면 다시 읽지
 * 않는다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PropertyMappingWriteResponse(
        Long id,
        Long sourceMappingId,
        Long sourceResourceId,
        Long propertyDefinitionId,
        Long sourceColumnId,
        String sourcePath,
        List<TransformStepPayload> transformSteps,
        long rowVersion,
        boolean reloadRequired,
        ReloadReason reloadReason) {

    public static PropertyMappingWriteResponse of(
            PropertyMappingResponse mapping, ReloadReason reloadReason) {
        return new PropertyMappingWriteResponse(
                mapping.id(),
                mapping.sourceMappingId(),
                mapping.sourceResourceId(),
                mapping.propertyDefinitionId(),
                mapping.sourceColumnId(),
                mapping.sourcePath(),
                mapping.transformSteps(),
                mapping.rowVersion(),
                reloadReason != null,
                reloadReason);
    }
}
