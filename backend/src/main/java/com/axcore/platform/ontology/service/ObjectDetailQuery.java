package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkGroupResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchAroundRequest;
import com.axcore.platform.ontology.dto.ObjectSearchAroundResult;
import java.util.List;
import org.springframework.data.domain.Page;

/** #137의 077 객체 상세와 078 활성 관계 조회, 관계 유형별 묶음 건수(351) 계약. */
public interface ObjectDetailQuery {

    /** 민감 속성 값만 권한에 맞게 가린다. 객체가 없거나 조회 범위 밖이면 RESOURCE_NOT_FOUND로 처리한다. */
    ObjectDetailResponse detail(LoginAccount actor, long objectId);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    ObjectDetailResponse detail(LoginAccount actor, DataPurpose purpose, long objectId);

    /**
     * 기준 객체가 없거나 조회 범위 밖이면 RESOURCE_NOT_FOUND로 처리한다. 활성 관계 중 상대 객체도 볼 수 있는 관계만 목록과 전체 건수에 포함하며, 보이는
     * 관계가 없으면 빈 페이지를 반환한다.
     */
    Page<ObjectLinkResponse> links(
            LoginAccount actor, long objectId, ObjectLinkSearchRequest request);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    Page<ObjectLinkResponse> links(
            LoginAccount actor,
            DataPurpose purpose,
            long objectId,
            ObjectLinkSearchRequest request);

    /**
     * links와 같은 범위의 관계를 관계 유형·방향별 건수로 모두 돌려준다. 건수가 많은 묶음부터 놓는다. 기준 객체가 없거나 조회 범위 밖이면
     * RESOURCE_NOT_FOUND로 처리한다.
     */
    List<ObjectLinkGroupResponse> linkGroups(LoginAccount actor, long objectId);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    List<ObjectLinkGroupResponse> linkGroups(
            LoginAccount actor, DataPurpose purpose, long objectId);

    /**
     * Search Around(379). 기준 객체 여러 개에서 관계 유형 하나로 한 홉을 건너 상대 객체 ID를 중복 없이 모은다. 078 공개 API는 바꾸지 않고
     * Agent의 Object Set Tool이 쓴다.
     *
     * <ul>
     *   <li>상대 유형은 관계 유형의 끝에서 정한다. OUT이면 도착 유형, IN이면 출발 유형이다. BOTH는 양 끝 유형이 같을 때만 된다.
     *   <li>방향을 생략하면 기준 객체 유형으로 정한다. 출발 유형이면 OUT, 도착 유형이면 IN, 양 끝 유형이 같으면 BOTH다. 정한 방향은 결과에 담는다.
     *   <li>관계 유형이 없거나 양 끝 중 하나라도 볼 수 없는 유형이면 RESOURCE_NOT_FOUND다.
     *   <li>기준 객체가 하나라도 없거나, 활성이 아니거나, 조회 범위 밖이면 078처럼 RESOURCE_NOT_FOUND다. 기준 객체 유형이 관계의 기준 쪽 끝과
     *       다르면 VALIDATION_FAILED다.
     *   <li>상대 객체 속성·기간 조건은 076과 같다. 볼 수 없는 민감 속성 조건은 FORBIDDEN이다(205).
     *   <li>기준 객체는 5000개까지 받는다. 결과가 5000건을 넘으면 BUSINESS_RULE_VIOLATION으로 거절한다.
     * </ul>
     */
    ObjectSearchAroundResult searchAround(LoginAccount actor, ObjectSearchAroundRequest request);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    ObjectSearchAroundResult searchAround(
            LoginAccount actor, DataPurpose purpose, ObjectSearchAroundRequest request);
}
