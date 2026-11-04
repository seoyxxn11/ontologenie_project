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

    private static final int TITLE_MAX_LENGTH = 200;

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

    /** API 085. 메시지를 저장할 때 마지막 메시지 시각을 갱신하고, 첫 질문이면 그 내용으로 제목을 정한다. */
    public void recordMessage(String question, Instant at) {
        if (title == null) {
            title =
                    question.length() > TITLE_MAX_LENGTH
                            ? question.substring(0, TITLE_MAX_LENGTH)
                            : question;
        }
        lastMessageAt = at;
    }
}
