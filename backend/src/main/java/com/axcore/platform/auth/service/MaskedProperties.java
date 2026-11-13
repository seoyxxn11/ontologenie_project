package com.axcore.platform.auth.service;

import java.util.List;
import java.util.Map;

/**
 * 민감 값을 가린 속성 맵(API 076·077의 properties·restrictedPropertyCodes). 가린 속성은 키를 남기고 값만 null이다.
 * restrictedPropertyCodes는 이번에 가린 코드뿐이라, 실제 값이 없는 null과 권한으로 가린 null을 이 목록으로 구분한다.
 */
public record MaskedProperties(
        Map<String, Object> properties,
        List<String> restrictedPropertyCodes,
        List<String> revealedPropertyCodes) {

    /**
     * @param restrictedPropertyCodes 가린 민감 속성 코드
     * @param revealedPropertyCodes 가리지 않고 값(null 아님)을 그대로 둔 민감 속성 코드. 민감 값 열람 기록(394)에 쓴다
     */
    public MaskedProperties {
        restrictedPropertyCodes = List.copyOf(restrictedPropertyCodes);
        revealedPropertyCodes = List.copyOf(revealedPropertyCodes);
    }
}
