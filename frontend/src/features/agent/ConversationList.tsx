import { useLayoutEffect, useRef, useState } from 'react'
import AgentIconButton from './AgentIconButton'
import Pagination from '../../components/Pagination'
import { NEW_CONVERSATION_TITLE } from './labels'
import type { AgentConversation } from './types'

type ConversationListProps = {
  conversations: AgentConversation[]
  selectedId: number | null
  creating: boolean
  onSelect: (id: number) => void
  onCreate: () => void
  deleting: boolean
  onDelete: (id: number) => void
}

function ConversationList({
  conversations,
  selectedId,
  creating,
  onSelect,
  onCreate,
  deleting,
  onDelete,
}: ConversationListProps) {
  const viewportRef = useRef<HTMLDivElement>(null)
  const rowRef = useRef<HTMLLIElement>(null)
  const [pageSize, setPageSize] = useState(16)
  const selectedIndex = Math.max(
    0,
    conversations.findIndex((conversation) => conversation.id === selectedId),
  )
  const [pagination, setPagination] = useState({
    selectedId,
    pageSize,
    page: Math.floor(selectedIndex / pageSize),
    followingSelection: true,
  })
  const lastPage = Math.max(0, Math.ceil(conversations.length / pageSize) - 1)
  const page = Math.min(pagination.page, lastPage)

  // 선택한 대화는 목록에서도 보이게 하고, 크기가 바뀌면 보던 목록의 첫 항목을 유지한다.
  if (
    pagination.selectedId !== selectedId ||
    pagination.pageSize !== pageSize
  ) {
    setPagination({
      selectedId,
      pageSize,
      followingSelection:
        pagination.selectedId !== selectedId || pagination.followingSelection,
      page:
        pagination.selectedId !== selectedId || pagination.followingSelection
          ? Math.floor(selectedIndex / pageSize)
          : Math.min(
              Math.floor((pagination.page * pagination.pageSize) / pageSize),
              lastPage,
            ),
    })
  } else if (pagination.page !== page) {
    setPagination({ ...pagination, page })
  }

  const hasConversations = conversations.length > 0
  const firstVisibleId = conversations[page * pageSize]?.id
  useLayoutEffect(() => {
    const viewport = viewportRef.current
    const row = rowRef.current
    if (!viewport || !row) return

    let availableHeight = 0
    let rowHeight = 0
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        if (entry.target === viewport)
          availableHeight = entry.contentRect.height
        if (entry.target === row) {
          const button = row.querySelector('.agent-conversation-select')
          const minimumHeight = button
            ? parseFloat(getComputedStyle(button).minHeight)
            : 0
          const borderHeight =
            parseFloat(getComputedStyle(row).borderBottomWidth) || 0
          // 늘어난 행 높이로 페이지 크기를 다시 줄이는 순환을 막는다.
          rowHeight =
            minimumHeight > 0
              ? minimumHeight + borderHeight
              : row.getBoundingClientRect().height
        }
      }
      if (availableHeight > 0 && rowHeight > 0) {
        setPageSize(Math.max(1, Math.floor(availableHeight / rowHeight)))
      }
    })
    observer.observe(viewport)
    observer.observe(row)
    return () => observer.disconnect()
  }, [hasConversations, firstVisibleId])

  const visibleConversations = conversations.slice(
    page * pageSize,
    (page + 1) * pageSize,
  )

  return (
    <aside
      id="agent-conversation-list"
      aria-label="대화방 목록"
      className="agent-sidebar"
    >
      <div className="agent-sidebar-heading">
        <h2>대화</h2>
        <AgentIconButton
          label="새 대화"
          icon="plus"
          onClick={onCreate}
          pending={creating}
        />
      </div>
      {!hasConversations ? (
        <p className="agent-sidebar-empty">아직 대화가 없어요.</p>
      ) : (
        <div ref={viewportRef} className="agent-conversation-viewport">
          <ul data-page-full={visibleConversations.length === pageSize}>
            {visibleConversations.map((conversation, index) => {
              const selected = conversation.id === selectedId
              return (
                <li
                  key={conversation.id}
                  ref={index === 0 ? rowRef : undefined}
                  className="agent-conversation-row"
                >
                  <button
                    type="button"
                    aria-pressed={selected}
                    onClick={() => onSelect(conversation.id)}
                    title={conversation.title ?? NEW_CONVERSATION_TITLE}
                    className="agent-conversation-select"
                  >
                    <span>{conversation.title ?? NEW_CONVERSATION_TITLE}</span>
                  </button>
                  <button
                    type="button"
                    disabled={deleting}
                    aria-label={`${conversation.title ?? NEW_CONVERSATION_TITLE} 삭제`}
                    onClick={() => {
                      if (
                        window.confirm(
                          '이 대화를 지울까요? 지운 대화는 다시 열 수 없어요.',
                        )
                      ) {
                        onDelete(conversation.id)
                      }
                    }}
                    className="agent-conversation-delete"
                  >
                    삭제
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      )}
      {hasConversations && (
        <div
          className="agent-sidebar-footer"
          data-long-page-numbers={
            Math.ceil(conversations.length / pageSize) >= 100
          }
        >
          <Pagination
            page={page}
            size={pageSize}
            totalElements={conversations.length}
            compact
            showSummary={false}
            onChange={(nextPage) =>
              setPagination({
                selectedId,
                pageSize,
                page: nextPage,
                followingSelection: false,
              })
            }
          />
        </div>
      )}
    </aside>
  )
}

export default ConversationList
