import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ErrorState, LoadingState } from '../../components/Feedback'
import ChatComposer from './ChatComposer'
import AgentIconButton from './AgentIconButton'
import type { ConversationDraft } from './AgentPage'
import MessageItem from './MessageItem'
import { fetchMessages, sendQuestion } from './api'
import { NEW_CONVERSATION_TITLE } from './labels'
import type {
  AgentConversation,
  AgentEntryState,
  ReferenceObject,
} from './types'

// 처리 중인 답변이 있으면 이 간격으로 084를 다시 읽는다(정책 문서 3.3).
const POLL_INTERVAL_MS = 1500

// 맨 아래에서 이만큼(px) 안에 있으면 맨 아래를 보고 있는 것으로 본다.
const NEAR_BOTTOM_PX = 80

function ConversationPanel({
  conversation,
  storedDraft,
  onDraftChange,
  focusRequest,
  sidebarCollapsed,
  onToggleSidebar,
}: {
  conversation: AgentConversation
  storedDraft?: ConversationDraft
  onDraftChange: (
    id: number,
    content: string,
    entry: AgentEntryState | null,
  ) => void
  focusRequest: number
  sidebarCollapsed: boolean
  onToggleSidebar: () => void
}) {
  const queryClient = useQueryClient()
  const location = useLocation()
  const navigate = useNavigate()
  const sending =
    useIsMutating({ mutationKey: ['agent', 'send', conversation.id] }) > 0
  const messagesQuery = useQuery({
    queryKey: ['agent', 'messages', conversation.id],
    queryFn: () => fetchMessages(conversation.id),
    // 들어올 때마다 대화를 새로 읽는다. 아래 참고 객체 판단과 입력란 표시가 "이 화면에서 새로 읽은 결과"를 기다리기 때문이다.
    refetchOnMount: 'always',
    refetchInterval: (query) =>
      query.state.data?.some((message) => message.status === 'RUNNING')
        ? POLL_INTERVAL_MS
        : false,
  })
  const messages = messagesQuery.data ?? []
  const running = messages.some((message) => message.status === 'RUNNING')

  // 대화 상자 높이는 고정하고 메시지 영역만 스크롤한다. 새 메시지가 오거나 처리 단계가 바뀌면 맨 아래로 내리되,
  // 위로 올려 이전 내용을 읽는 중이면 끌어내리지 않는다. 처음 열 때와 질문을 보낼 때는 맨 아래로 간다.
  const logRef = useRef<HTMLDivElement>(null)
  const followBottomRef = useRef(true)
  const last = messages.at(-1)
  const lastChange = `${messages.length}:${last?.id}:${last?.status}:${last?.answerMeta.stage}:${last?.content.length}`
  useEffect(() => {
    const log = logRef.current
    if (log && followBottomRef.current) {
      log.scrollTop = log.scrollHeight
    }
  }, [lastChange])

  function handleLogScroll() {
    const log = logRef.current
    if (log) {
      followBottomRef.current =
        log.scrollHeight - log.scrollTop - log.clientHeight <= NEAR_BOTTOM_PX
    }
  }

  // Explorer의 [질문하기]로 이 대화에 들어왔으면 그 객체를 첫 질문에만 함께 보낸다(152).
  // 다음 질문부터 같은 대상을 이어 쓰거나 다른 대상으로 바꾸는 일은 서버가 맡는다(147). 질문마다 보내면 다른 객체를 물어도
  // 처음 객체로 답할 수 있기 때문이다. 첫 질문 전까지는 주소 기록(history state)에 남아 새로고침해도 유지되고, 화면에는 따로
  // 참고 객체와 추천 질문에 반영한다(150).
  // 주소 기록만 믿지 않고, 이 화면에서 새로 읽은 대화가 비어 있을 때만 참고 객체를 쓴다. 첫 질문을 보내는 사이 다른 대화로
  // 옮기거나 새로고침하면 기록이 지워지지 않고 남는데, 나중에 돌아왔을 때 다시 붙지 않게 하기 위해서다.
  const savedEntry = storedDraft
    ? storedDraft.entry
    : entryFor(location.state, conversation.id)
  const freshlyLoaded =
    messagesQuery.isSuccess && messagesQuery.isFetchedAfterMount
  // 미리 채운 질문은 입력란을 처음 그릴 때만 들어가므로, 기록이 있으면 대화를 다 읽은 뒤 입력란을 그린다. 읽다 실패하면
  // 입력란 없이 오류와 [다시 시도]만 보여 주어, 다시 읽은 뒤 미리 채운 질문과 참고 객체가 함께 나오게 한다.
  // 입력란을 처음 그릴 때 참고 객체를 쓸지도 정하고 그 뒤로는 바꾸지 않는다. 나중에 다시 읽다 실패해도 입력란과 쓰던 질문,
  // 추천 질문이 그대로 남는다.
  const [decision, setDecision] = useState<{
    entry: AgentEntryState | null
  } | null>(null)
  const [entryConsumed, setEntryConsumed] = useState(false)
  if (decision === null && (!savedEntry || freshlyLoaded)) {
    setDecision({
      entry: savedEntry && messages.length === 0 ? savedEntry : null,
    })
  }
  const composerReady = decision !== null
  // 첫 질문을 보내 주소 기록을 지우면 더는 쓰지 않는다.
  const entry = !entryConsumed && savedEntry ? (decision?.entry ?? null) : null
  const reference: ReferenceObject | null = entry
    ? {
        id: entry.referenceObjectId,
        name: entry.referenceObjectName,
        typeName: entry.referenceObjectTypeName,
      }
    : null

  // 주소 기록의 상태만 바꾼다. 화면 이동은 하지 않는다.
  function replaceEntry(next: AgentEntryState | null) {
    void navigate(`${location.pathname}${location.search}`, {
      replace: true,
      state: next,
    })
  }

  const sendMutation = useMutation({
    mutationKey: ['agent', 'send', conversation.id],
    mutationFn: (content: string) =>
      sendQuestion(
        conversation.id,
        reference
          ? { content, referenceObjectIds: [reference.id] }
          : { content },
      ),
    onSuccess: () => {
      setEntryConsumed(true)
      onDraftChange(conversation.id, '', null)
      // 접수된 질문과 처리 중 답변을 다시 읽고, 첫 질문이면 바뀐 제목도 목록에 반영한다.
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'messages', conversation.id],
      })
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
    },
  })

  async function handleSend(content: string) {
    // 직접 보낸 질문과 그 답은 이전 내용을 읽던 중이어도 보이게 맨 아래로 따라간다.
    followBottomRef.current = true
    await sendMutation.mutateAsync(content, {
      // 첫 질문을 보냈으면 참고 객체와 미리 채운 질문을 주소 기록에서 지운다. 이 콜백은 화면이 그대로일 때만 실행되므로,
      // 보내는 사이 다른 대화로 옮겼다면 그 주소를 원래 대화로 덮어쓰지 않는다.
      onSuccess: () => {
        if (entry) {
          replaceEntry(null)
        }
      },
    })
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
      return <p className="agent-new-prompt">어떤 업무를 확인할까요?</p>
    }
    return messages.map((message) => (
      <MessageItem key={message.id} message={message} />
    ))
  }

  // 대화 목록과 입력란은 유지하고 메시지 영역 안에서 이전 답변을 읽는다.
  return (
    <section aria-label="현재 대화" className="agent-conversation">
      <div className="agent-conversation-header">
        <AgentIconButton
          label={sidebarCollapsed ? '대화 목록 펼치기' : '대화 목록 접기'}
          icon={sidebarCollapsed ? 'expand' : 'collapse'}
          aria-expanded={!sidebarCollapsed}
          aria-controls="agent-conversation-list"
          onClick={onToggleSidebar}
        />
        <h2 className="agent-conversation-heading">
          {conversation.title ?? NEW_CONVERSATION_TITLE}
        </h2>
      </div>
      <div
        ref={logRef}
        role="log"
        aria-label="대화 내용"
        aria-live="polite"
        tabIndex={0}
        onScroll={handleLogScroll}
        className="agent-message-log"
        data-empty={messagesQuery.isSuccess && messages.length === 0}
      >
        {renderMessages()}
      </div>
      {sendMutation.isError && (
        <div className="agent-send-error">
          <ErrorState
            error={sendMutation.error}
            title="질문을 보내지 못했습니다."
          />
        </div>
      )}
      {composerReady && (
        <ChatComposer
          draftValue={storedDraft?.content ?? entry?.draft ?? ''}
          onDraftChange={(content) =>
            onDraftChange(conversation.id, content, entry)
          }
          focusRequest={focusRequest}
          reference={reference}
          disabled={running || sending}
          onSend={handleSend}
        />
      )}
    </section>
  )
}

export default ConversationPanel

// 주소 기록의 상태가 이 대화의 참고 객체이면 돌려준다. 이름·유형이 없는 예전 기록은 참고 객체 없음으로 본다(150).
function entryFor(state: unknown, conversationId: number) {
  const entry = state as Partial<AgentEntryState> | null
  if (
    entry?.conversationId !== conversationId ||
    typeof entry.referenceObjectId !== 'number' ||
    typeof entry.referenceObjectName !== 'string' ||
    typeof entry.referenceObjectTypeName !== 'string'
  ) {
    return null
  }
  return entry as AgentEntryState
}
