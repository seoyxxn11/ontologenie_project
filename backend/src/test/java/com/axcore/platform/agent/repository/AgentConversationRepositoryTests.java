package com.axcore.platform.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.domain.AgentConversation;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 오래된 대화 요약 저장(333)을 실제 PostgreSQL로 본다. 시험마다 되돌린다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentConversationRepositoryTests {
    private static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");

    @Autowired AgentConversationRepository conversations;
    @Autowired EntityManager entityManager;
    @Autowired JdbcClient jdbc;

    private long conversationId;

    @BeforeEach
    void openConversation() {
        long owner =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values ('summary-' || gen_random_uuid(), '요약 시험 사용자', 'x',
                                        (select id from role where code = 'PLANNER'), false)
                                returning id
                                """)
                        .query(Long.class)
                        .single();
        conversationId = conversations.saveAndFlush(AgentConversation.start(owner, NOW)).getId();
    }

    @Test
    void savesSummaryOnlyWhenRangeIsStillWhatWasRead() {
        assertThat(conversations.saveSummary(conversationId, 0L, "질문 1을 물었다.", 12L, null))
                .isEqualTo(1);
        // 같은 범위(요약 없음)를 읽고 늦게 끝난 요약은 먼저 저장된 요약을 덮지 않는다.
        assertThat(conversations.saveSummary(conversationId, 0L, "늦게 끝난 요약", 14L, null)).isZero();
        assertThat(
                        conversations.saveSummary(
                                conversationId, 12L, "질문 1·2를 물었다.", 14L, "{\"policy\":\"p\"}"))
                .isEqualTo(1);

        assertThat(stored())
                .containsEntry("summary", "질문 1·2를 물었다.")
                .containsEntry("summarized_until_message_id", 14L)
                .containsEntry("summary_access_scope", "{\"policy\":\"p\"}");
    }

    @Test
    void savingConversationForNewQuestionDoesNotOverwriteSummary() {
        AgentConversation loaded = conversations.findById(conversationId).orElseThrow();
        // 질문을 받는 동안 다른 처리가 요약을 저장했다.
        conversations.saveSummary(conversationId, 0L, "질문 1을 물었다.", 12L, null);

        // 질문을 받은 쪽은 요약이 없던 때의 Entity를 고쳐 저장한다.
        loaded.recordMessage("새 질문", NOW.plusSeconds(60));
        entityManager.flush();

        assertThat(stored())
                .containsEntry("summary", "질문 1을 물었다.")
                .containsEntry("summarized_until_message_id", 12L);
    }

    private Map<String, Object> stored() {
        return jdbc.sql(
                        """
                        select summary, summarized_until_message_id, summary_access_scope
                          from agent_conversation
                         where id = :id
                        """)
                .param("id", conversationId)
                .query()
                .singleRow();
    }
}
