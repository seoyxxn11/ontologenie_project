import Button from '../../components/Button'
import { NEW_CONVERSATION_TITLE } from './labels'
import type { AgentConversation } from './types'

type ConversationListProps = {
  conversations: AgentConversation[]
  selectedId: number | null
  creating: boolean
  onSelect: (id: number) => void
  onCreate: () => void
}

function ConversationList({
  conversations,
  selectedId,
  creating,
  onSelect,
  onCreate,
}: ConversationListProps) {
  return (
    <aside
      aria-label="대화방 목록"
      className="rounded-xl border border-slate-200 bg-white"
    >
      <div className="flex items-center justify-between border-b border-slate-200 p-3">
        <h2 className="text-sm font-semibold text-slate-900">대화</h2>
        <Button variant="secondary" onClick={onCreate} disabled={creating}>
          + 새 대화
        </Button>
      </div>
      {conversations.length === 0 ? (
        <p className="p-3 text-sm text-slate-500">아직 대화가 없어요.</p>
      ) : (
        <ul className="p-2">
          {conversations.map((conversation) => {
            const selected = conversation.id === selectedId
            return (
              <li key={conversation.id}>
                <button
                  type="button"
                  aria-pressed={selected}
                  onClick={() => onSelect(conversation.id)}
                  className={`w-full truncate rounded-lg px-3 py-2 text-left text-sm ${selected ? 'bg-slate-100 font-semibold text-slate-900' : 'text-slate-700 hover:bg-slate-50'}`}
                >
                  {conversation.title ?? NEW_CONVERSATION_TITLE}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </aside>
  )
}

export default ConversationList
