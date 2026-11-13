package com.axcore.platform.auth.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.auth.repository.DataScopeRepository;
import com.axcore.platform.global.security.LoginAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 객체·민감 속성 조회 권한(S15P31C106-205). 객체를 돌려주는 조회는 먼저 {@link #of}로 사용자의 범위를 받고, 그 범위로 거르고 가린다. 기준은
 * {@link DataAccess}에 적었다.
 */
@Service
@RequiredArgsConstructor
public class DataAccessPolicy {

    private final DataScopeRepository dataScopeRepository;

    /** 사람이 화면·API 응답으로 볼 범위({@link DataPurpose#VIEW}). */
    @Transactional(readOnly = true)
    public DataAccess of(LoginAccount actor) {
        return scope(actor, DataPurpose.VIEW);
    }

    /**
     * 외부 언어 모델에 보낼 범위({@link DataPurpose#MODEL}). 보이는 유형은 {@link #of}와 같고, 민감 값은 역할과 관계없이 가린다.
     * Agent 도구 결과와 Agent가 실행한 Action의 문구처럼 모델 입력이 되는 값은 이 범위로 읽는다.
     */
    @Transactional(readOnly = true)
    public DataAccess forModel(LoginAccount actor) {
        return scope(actor, DataPurpose.MODEL);
    }

    /**
     * 내부 서비스 토큰으로 부른 정의 조회의 범위({@link DataPurpose#DEFINITIONS}). 모든 유형과 민감 속성의 정의를 본다. 지금 내부 API를
     * 부르는 곳은 AI 분석(FastAPI)의 모델 요약(API 043)뿐이다. 객체 값을 읽는 데는 쓸 수 없다({@link
     * DataAccess#requireObjectValues}). 사용자를 대신해 값을 읽는 내부 호출이 생기면 그 사용자의 {@link #of}나 {@link
     * #forModel}을 쓴다.
     */
    @Transactional(readOnly = true)
    public DataAccess forDefinitions() {
        return new DataAccess(
                dataScopeRepository.findAll(RoleCode.ADMIN.name(), true), DataPurpose.DEFINITIONS);
    }

    private DataAccess scope(LoginAccount actor, DataPurpose purpose) {
        boolean admin = RoleCode.ADMIN.name().equals(actor.roleCode());
        return new DataAccess(dataScopeRepository.findAll(actor.roleCode(), admin), purpose);
    }
}
