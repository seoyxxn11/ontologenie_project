package com.axcore.platform.agent.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Agent 대화방 (FN-AGT-01·06). 제목은 첫 질문 때 정하고(085), 그 전에는 null이다. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long accountId;

    private String title;

    private Instant startedAt;

    private Instant lastMessageAt;

    private AgentConversation(Long accountId, Instant now) {
        this.accountId = accountId;
        this.startedAt = now;
        this.lastMessageAt = now;
    }

    /** API 082. 로그인 사용자를 소유자로 빈 대화를 연다. */
    public static AgentConversation start(Long accountId, Instant now) {
        return new AgentConversation(accountId, now);
    }
}
