package com.axcore.platform.mapping.dto;

import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * API 036. 이번 수정 때문에 다음 수집에서 테이블을 처음부터 다시 읽는지(reloadRequired)와 그 이유를 더한다. 이유는 IDENTITY_CHANGED(객체가
 * 없는 매핑의 원천 자원·유형·키 변경) 또는 REACTIVATED(멈춘 매핑을 다시 켬)다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ObjectMappingUpdateResponse(
        Long id,
        Long sourceResourceId,
        Long objectTypeId,
        List<String> keyColumns,
        Map<String, Object> rowFilter,
        long rowVersion,
        DefinitionOrigin origin,
        Long proposalItemId,
        boolean isActive,
        boolean reloadRequired,
        String reloadReason) {

    public static ObjectMappingUpdateResponse of(
            ObjectMappingResponse mapping, String reloadReason) {
        return new ObjectMappingUpdateResponse(
                mapping.id(),
                mapping.sourceResourceId(),
                mapping.objectTypeId(),
                mapping.keyColumns(),
                mapping.rowFilter(),
                mapping.rowVersion(),
                mapping.origin(),
                mapping.proposalItemId(),
                mapping.isActive(),
                reloadReason != null,
                reloadReason);
    }
}
