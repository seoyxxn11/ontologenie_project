package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;

/** #138의 078 관계 근거 조회 계약. */
public interface ObjectEvidenceQuery {

    /**
     * 기준 객체에 연결된 활성 관계 한 건의 근거를 요청자의 권한으로 가려 반환한다. 기준 객체 또는 관계가 없거나 관계 양쪽의 객체를 모두 볼 수 없으면
     * RESOURCE_NOT_FOUND로 처리한다.
     */
    ObjectLinkEvidenceResponse linkEvidence(LoginAccount actor, long objectId, long linkId);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    ObjectLinkEvidenceResponse linkEvidence(
            LoginAccount actor, DataPurpose purpose, long objectId, long linkId);
}
