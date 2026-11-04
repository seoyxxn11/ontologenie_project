package com.axcore.platform.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 대화 메시지 (FN-AGT-01·04·05). 사용자 질문(USER)과 Agent 답변(AGENT). 답변은 처리 중(RUNNING)으로 먼저 저장하고, 처리가 끝나면 결과로
 * 바꾼다(정책 문서 3장).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long conversationId;

    @Enumerated(EnumType.STRING)
    private MessageSender sender;

    @Column(columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> context;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> answerMeta;

    @Enumerated(EnumType.STRING)
    private MessageStatus status;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant createdAt;

    private AgentMessage(
            Long conversationId,
            MessageSender sender,
            String content,
            Map<String, Object> context,
            MessageStatus status,
            Map<String, Object> answerMeta,
            Instant createdAt) {
        this.conversationId = conversationId;
        this.sender = sender;
        this.content = content;
        this.context = new LinkedHashMap<>(context);
        this.status = status;
        this.answerMeta = new LinkedHashMap<>(answerMeta);
        this.createdAt = createdAt;
    }

    /** API 085. 사용자 질문. 참고 객체·기간은 context에 보존한다. */
    public static AgentMessage question(
            Long conversationId, String content, Map<String, Object> context, Instant now) {
        return new AgentMessage(
                conversationId, MessageSender.USER, content, context, null, Map.of(), now);
    }

    /** API 085. 처리 중인 답변. 첫 단계는 질문 해석이다. */
    public static AgentMessage runningAnswer(Long conversationId, Instant now) {
        return new AgentMessage(
                conversationId,
                MessageSender.AGENT,
                "",
                Map.of(),
                MessageStatus.RUNNING,
                Map.of("stage", AnswerStage.INTERPRETING.name()),
                now);
    }

    public boolean isRunning() {
        return status == MessageStatus.RUNNING;
    }

    /** 답변을 끝내지 못했다. 사용자에게 보여 줄 안내와 실패 이유를 남기고 단계 정보는 지운다. */
    public void fail(String content, String errorMessage) {
        this.status = MessageStatus.FAILED;
        this.content = content;
        this.errorMessage = errorMessage;
        this.answerMeta = new LinkedHashMap<>(Map.of("syncStatus", "NONE", "results", List.of()));
    }
}
