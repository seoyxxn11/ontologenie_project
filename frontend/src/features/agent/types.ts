export type AgentConversation = {
  id: number
  title: string | null
  startedAt: string
  lastMessageAt: string
}

export type AgentStage = 'INTERPRETING' | 'SYNCING' | 'QUERYING' | 'ANSWERING'

export type AgentMessageStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type SyncStatus = 'NONE' | 'COMPLETED' | 'TIMED_OUT' | 'FAILED'

export type ResultCategory = 'FOUND' | 'NO_DATA' | 'UNLINKED' | 'DENIED'

export type AgentResultSource = {
  objectId?: number
  sourceResourceId?: number
  linkId?: number
}

export type AgentResult = {
  label: string
  toolCode: string
  status: 'SUCCEEDED' | 'FAILED'
  resultCategory: ResultCategory | null
  sources: AgentResultSource[]
  dataAsOf: string | null
  estimated: boolean
  errorMessage: string | null
}

export type AnswerMeta = {
  stage?: AgentStage
  syncStatus?: SyncStatus
  results?: AgentResult[]
}

export type AgentMessage = {
  id: number
  conversationId: number
  sender: 'USER' | 'AGENT'
  content: string
  context: Record<string, unknown>
  status: AgentMessageStatus | null
  errorMessage: string | null
  answerMeta: AnswerMeta
  createdAt: string
}

export type QuestionRequest = {
  content: string
  referenceObjectIds?: number[]
  context?: Record<string, unknown>
}
