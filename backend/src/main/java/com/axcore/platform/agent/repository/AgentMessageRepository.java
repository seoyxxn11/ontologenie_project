package com.axcore.platform.agent.repository;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    Page<AgentMessage> findByConversationId(Long conversationId, Pageable pageable);

    /** 대화의 최근 메시지를 최신 순으로 읽는다. 질문 해석에 넘길 최근 대화 원문을 고를 때 쓴다(147). */
    List<AgentMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** 대화에서 두 ID 사이(양 끝 제외)의 메시지를 최신 순으로 읽는다. 요약이 없던 대화의 첫 요약에 넣을 메시지를 고를 때 쓴다(333). */
    List<AgentMessage> findByConversationIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(
            Long conversationId, Long afterId, Long beforeId, Pageable pageable);

    /** 대화에서 두 ID 사이(양 끝 제외)의 메시지를 오래된 순으로 읽는다. 이전 요약에 이어 합칠 메시지를 고를 때 쓴다(333). */
    List<AgentMessage> findByConversationIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(
            Long conversationId, Long afterId, Long beforeId, Pageable pageable);

    /**
     * 대화에서 이 메시지(id)까지 사용자가 한 질문 수. 턴에 대화 전체 기준 순번을 붙일 때 쓴다(333). 되묻기 바로 다음 사용자 말은 그 되묻기에 대한 답이라 세지
     * 않는다(RecentTurns의 순번과 같은 기준). 바로 앞 메시지의 답변 메타(jsonb)에 clarification이 있는지 봐야 해 JPQL로 쓸 수 없어
     * native로 쓴다.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    select count(*)
                      from agent_message m
                     where m.conversation_id = :conversationId
                       and m.sender = 'USER'
                       and m.id <= :id
                       and not coalesce((select (p.answer_meta -> 'clarification') is not null
                                           from agent_message p
                                          where p.conversation_id = m.conversation_id
                                            and p.id < m.id
                                          order by p.id desc
                                          limit 1), false)
                    """)
    long countQuestionsUpTo(@Param("conversationId") Long conversationId, @Param("id") Long id);

    boolean existsByConversationIdAndStatus(Long conversationId, MessageStatus status);

    /**
     * 5분 넘게 처리 중인 답변을 잠그고 읽는다(정책 문서 3.4). 처리기가 같은 답변의 결과를 저장하는 중이면 그 저장이 끝날 때까지 기다린 뒤, 이미 끝난 답변은
     * 고르지 않는다. 늦게 끝난 결과를 정리가 덮어쓰지 않게 한다(160). 여러 건이면 id 순서로 잠가 서로 다른 순서로 잠그다 교착되지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AgentMessage> findByConversationIdAndStatusAndCreatedAtBeforeOrderByIdAsc(
            Long conversationId, MessageStatus status, Instant before);

    /** 답변 결과를 저장하기 전에 그 답변을 잠그고 읽는다. 5분 정리와 동시에 저장해 서로 덮어쓰지 않게 한다(160). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from AgentMessage m where m.id = :id")
    Optional<AgentMessage> findForUpdateById(@Param("id") Long id);

    List<AgentMessage> findTop10ByConversationIdAndSenderAndStatusOrderByIdDesc(
            Long conversationId, MessageSender sender, MessageStatus status);

    /** 메시지가 속한 대화 ID. 재배정 계획을 Agent 대화에 연결할 때 쓴다. 메시지가 없으면 비어 있다. */
    @Query("select m.conversationId from AgentMessage m where m.id = :id")
    Optional<Long> findConversationIdById(@Param("id") Long id);

    /** 대화 메시지를 오래된 순으로, 권한 범위 판단에 필요한 것만(413). 084를 다시 부를 때마다 읽으므로 답변 본문은 읽지 않는다. */
    @Query(
            """
            select new com.axcore.platform.agent.repository.MessageScopeMark(m.id, m.sender, m.context)
              from AgentMessage m
             where m.conversationId = :conversationId
             order by m.id
            """)
    List<MessageScopeMark> findScopeMarks(@Param("conversationId") long conversationId);
}
