package com.axcore.platform.audit.domain;

import com.axcore.platform.global.security.LoginAccount;
import java.util.Objects;

/** 기록의 실행자. 역할은 실행 당시 값을 그대로 남긴다(나중에 역할이 바뀌어도 기록은 그대로). */
public record Actor(ActorType type, Long accountId, String roleCode) {

    public Actor {
        Objects.requireNonNull(type, "type");
        if (type == ActorType.SYSTEM) {
            if (accountId != null || roleCode != null) {
                throw new IllegalArgumentException("SYSTEM 실행자는 계정과 역할이 없습니다.");
            }
        } else {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(roleCode, "roleCode");
        }
    }

    public static Actor user(LoginAccount loginAccount) {
        return new Actor(ActorType.USER, loginAccount.id(), loginAccount.roleCode());
    }

    /** Agent가 사용자를 대신해 실행할 때. 기록에 agentMessageId도 넣어야 한다. */
    public static Actor agent(LoginAccount loginAccount) {
        return new Actor(ActorType.AGENT, loginAccount.id(), loginAccount.roleCode());
    }

    /** 사람 없이 시스템이 하거나 끝낸 일. 배치의 주기 작업, 작업 결과 줄. */
    public static Actor system() {
        return new Actor(ActorType.SYSTEM, null, null);
    }
}
