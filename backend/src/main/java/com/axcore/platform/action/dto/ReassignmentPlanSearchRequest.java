package com.axcore.platform.action.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** API 093 조회 조건. 비어 있는 조건으로는 거르지 않는다. */
public record ReassignmentPlanSearchRequest(
        @Min(value = 1, message = "fromEquipmentId는 1 이상이어야 합니다.") Long fromEquipmentId,
        @Min(value = 1, message = "createdBy는 1 이상이어야 합니다.") Long createdBy,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size,
        @Size(max = 100, message = "createdByName은 100자 이하여야 합니다.") String createdByName) {}
