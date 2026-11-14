import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import PageHeader from '../../components/PageHeader'
import ConversationList from './ConversationList'
import ConversationPanel from './ConversationPanel'
import AgentIconButton from './AgentIconButton'
import {
  createConversation,
  deleteConversation,
  fetchConversations,
} from './api'
import type { AgentConversation, AgentEntryState } from './types'

export type ConversationDraft = {
  content: string
  entry: AgentEntryState | null
}
import './agent.css'

function AgentPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [drafts, setDrafts] = useState<Record<number, ConversationDraft>>({})
  const [focusRequest, setFocusRequest] = useState(0)
  const keepDraft = useCallback(
    (id: number, content: string, entry: AgentEntryState | null) => {
      setDrafts((previous) =>
        previous[id]?.content === content && previous[id]?.entry === entry
          ? previous
          : { ...previous, [id]: { content, entry } },
      )
    },
    [],
  )

  const conversationsQuery = useQuery({
    queryKey: ['agent', 'conversations'],
    queryFn: fetchConversations,
  })

  const createMutation = useMutation({
    mutationFn: createConversation,
    onSuccess: (created) => {
      // 목록을 다시 받기 전에도 새 대화가 바로 보이도록 맨 앞에 넣는다.
      queryClient.setQueryData<AgentConversation[]>(
        ['agent', 'conversations'],
        (previous) => [created, ...(previous ?? [])],
      )
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
      setSearchParams({ conversation: String(created.id) })
      setFocusRequest((previous) => previous + 1)
    },
  })

  // 지운 뒤 목록을 다시 읽고, 열려 있던 대화면 주소에서 뺀다.
  const deleteMutation = useMutation({
    mutationFn: deleteConversation,
    onSuccess: (_, deletedId) => {
      setDrafts((previous) => {
        const next = { ...previous }
        delete next[deletedId]
        return next
      })
      queryClient.removeQueries({ queryKey: ['agent', 'messages', deletedId] })
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
      if (Number(searchParams.get('conversation')) === deletedId) {
        setSearchParams({})
      }
    },
  })

  const conversations = conversationsQuery.data ?? []
  const requestedId = Number(searchParams.get('conversation'))
  // 주소의 대화가 목록에 없으면 가장 최근 대화를 연다.
  const selected =
    conversations.find((conversation) => conversation.id === requestedId) ??
    conversations[0]

  function renderBody() {
    if (conversationsQuery.isPending) {
      return <LoadingState message="대화 목록을 불러오는 중…" />
    }
    if (conversationsQuery.isError) {
      return (
        <ErrorState
          error={conversationsQuery.error}
          onRetry={() => void conversationsQuery.refetch()}
        />
      )
    }
    return (
      <div
        className="agent-workspace"
        data-sidebar-collapsed={sidebarCollapsed}
      >
        <div className="agent-sidebar-slot" hidden={sidebarCollapsed}>
          <ConversationList
            conversations={conversations}
            selectedId={selected?.id ?? null}
            creating={createMutation.isPending}
            onSelect={(id) => setSearchParams({ conversation: String(id) })}
            onCreate={() => {
              if (selected?.title === null) {
                setFocusRequest((previous) => previous + 1)
              } else {
                createMutation.mutate()
              }
            }}
            deleting={deleteMutation.isPending}
            onDelete={(id) => deleteMutation.mutate(id)}
          />
        </div>
        {selected ? (
          <ConversationPanel
            key={selected.id}
            conversation={selected}
            storedDraft={drafts[selected.id]}
            onDraftChange={keepDraft}
            focusRequest={focusRequest}
            sidebarCollapsed={sidebarCollapsed}
            onToggleSidebar={() => setSidebarCollapsed((previous) => !previous)}
          />
        ) : (
          <section className="agent-empty-panel" aria-label="현재 대화">
            <div className="agent-conversation-header">
              <AgentIconButton
                label={sidebarCollapsed ? '대화 목록 펼치기' : '대화 목록 접기'}
                icon={sidebarCollapsed ? 'expand' : 'collapse'}
                aria-expanded={!sidebarCollapsed}
                aria-controls="agent-conversation-list"
                onClick={() => setSidebarCollapsed((previous) => !previous)}
              />
              <h2 className="agent-conversation-heading">새 대화</h2>
              {sidebarCollapsed && (
                <AgentIconButton
                  label="새 대화"
                  icon="plus"
                  pending={createMutation.isPending}
                  onClick={() => createMutation.mutate()}
                />
              )}
            </div>
            <EmptyState message="새 대화 버튼으로 대화를 시작하세요." />
          </section>
        )}
      </div>
    )
  }

  return (
    <div className="agent-page">
      <PageHeader
        title="AI 업무 도우미"
        description="업무 정보를 질문하고, 답변의 출처와 실행 결과를 함께 확인하세요."
      />
      {createMutation.isError && (
        <div className="mb-4">
          <ErrorState
            error={createMutation.error}
            title="대화를 만들지 못했습니다."
          />
        </div>
      )}
      {deleteMutation.isError && (
        <div className="mb-4">
          <ErrorState
            error={deleteMutation.error}
            title="대화를 지우지 못했습니다."
          />
        </div>
      )}
      {renderBody()}
    </div>
  )
}

export default AgentPage
