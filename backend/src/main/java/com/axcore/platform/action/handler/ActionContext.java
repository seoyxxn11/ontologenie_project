package com.axcore.platform.action.handler;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import java.util.Map;

/** Handler에 넘기는 실행 요청. input은 inputSchema 검사를 통과한 값이다. agentMessageId는 Agent 경유일 때만 있다. */
public record ActionContext(
        ActionDefinition definition,
        LoginAccount actor,
        EntryPoint entryPoint,
        Map<String, Object> input,
        Long agentMessageId) {

    /**
     * Handler가 객체를 읽을 용도(S15P31C106-384·390). Agent가 실행하면 차단 문구와 결과가 외부 모델로 돌아가므로 모델 용도(민감 값 항상
     * 가림), 그 밖에는 사람이 보는 용도다.
     */
    public DataPurpose dataPurpose() {
        return entryPoint == EntryPoint.AGENT ? DataPurpose.MODEL : DataPurpose.VIEW;
    }
}
