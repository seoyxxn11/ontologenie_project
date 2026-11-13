package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.ontology.repository.PropertyValueSampleRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유형의 문자 속성에 저장된 값 종류를 사용자 조회 범위로 돌려준다(359). Agent가 목록 조회 조건 값을 저장된 값 그대로 고르게 하려고 쓴다. 볼 수 없는 유형은 빈
 * 결과이고, 민감(RESTRICTED) 속성은 그 유형의 민감 값을 볼 수 있을 때만 넣는다(205).
 */
@Service
@RequiredArgsConstructor
public class PropertyValueSampleService {
    private final DataAccessPolicy dataAccessPolicy;
    private final PropertyDefinitionRepository properties;
    private final PropertyValueSampleRepository samples;

    /**
     * 속성 코드별 값 종류(값 순서). 값이 없는 속성은 빈 목록이다. 값 종류가 limit를 넘는 속성은 상태 같은 범주 값이 아니라고 보고 넣지 않는다. 사용 중인
     * 객체만 보고 null·빈 값은 넣지 않는다. 값 종류는 Agent가 모델에 넘기므로 부르는 쪽이 용도를 정한다(모델 용도면 민감 속성은 넣지 않는다, 384).
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> stringValues(
            LoginAccount actor, DataPurpose purpose, long objectTypeId, int limit) {
        DataAccess access = dataAccessPolicy.of(actor, purpose);
        if (!access.canView(objectTypeId)) {
            return Map.of();
        }
        List<String> codes =
                properties.findByObjectTypeIdOrderById(objectTypeId).stream()
                        .filter(property -> property.getDataType() == PropertyDataType.STRING)
                        .filter(
                                property ->
                                        property.getSensitivity() != PropertySensitivity.RESTRICTED
                                                || access.canViewRestricted(objectTypeId))
                        .map(PropertyDefinition::getCode)
                        .toList();
        Map<String, List<String>> found = samples.distinctValues(objectTypeId, codes, limit);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String code : codes) {
            List<String> values = found.getOrDefault(code, List.of());
            if (values.size() <= limit) {
                result.put(code, List.copyOf(values));
            }
        }
        return result;
    }
}
