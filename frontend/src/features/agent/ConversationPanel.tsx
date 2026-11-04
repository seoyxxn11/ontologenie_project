import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import ChatComposer from './ChatComposer'
import MessageItem from './MessageItem'
import { fetchMessages, sendQuestion } from './api'
import { NEW_CONVERSATION_TITLE } from './labels'
import type { AgentConversation } from './types'

// 처리 중인 답변이 있으면 이 간격으로 084를 다시 읽는다(정책 문서 3.3).
const POLL_INTERVAL_MS = 1500

function ConversationPanel({
  conversation,
}: {
  conversation: AgentConversation
}) {
  const queryClient = useQueryClient()

  const messagesQuery = useQuery({
    queryKey: ['agent', 'messages', conversation.id],
    queryFn: () => fetchMessages(conversation.id),
    refetchInterval: (query) =>
      query.state.data?.some((message) => message.status === 'RUNNING')
        ? POLL_INTERVAL_MS
        : false,
  })

  const sendMutation = useMutation({
    mutationFn: (content: string) => sendQuestion(conversation.id, { content }),
    onSuccess: () => {
      // 접수된 질문과 처리 중 답변을 다시 읽고, 첫 질문이면 바뀐 제목도 목록에 반영한다.
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'messages', conversation.id],
      })
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
    },
  })

  const messages = messagesQuery.data ?? []
  const running = messages.some((message) => message.status === 'RUNNING')

  async function handleSend(content: string) {
    await sendMutation.mutateAsync(content)
  }

  function renderMessages() {
    if (messagesQuery.isPending) {
      return <LoadingState message="대화를 불러오는 중…" />
    }
    if (messagesQuery.isError) {
      return (
        <ErrorState
          error={messagesQuery.error}
          onRetry={() => void messagesQuery.refetch()}
        />
      )
    }
    if (messages.length === 0) {
      return (
        <EmptyState message="업무에 대해 질문해 주세요. 예: PR-03의 상태를 알려줘" />
      )
    }
    return messages.map((message) => (
      <MessageItem key={message.id} message={message} />
    ))
  }

  return (
    <section
      aria-label="현재 대화"
      className="flex min-h-[32rem] flex-col rounded-xl border border-slate-200 bg-white"
    >
      <h2 className="border-b border-slate-200 p-3 text-sm font-semibold text-slate-900">
        {conversation.title ?? NEW_CONVERSATION_TITLE}
      </h2>
      <div aria-live="polite" className="flex-1 space-y-4 overflow-y-auto p-4">
        {renderMessages()}
      </div>
      {sendMutation.isError && (
        <div className="px-3">
          <ErrorState
            error={sendMutation.error}
            title="질문을 보내지 못했습니다."
          />
        </div>
      )}
      <ChatComposer
        disabled={running || sendMutation.isPending}
        onSend={handleSend}
      />
    </section>
  )
}

export default ConversationPanel
