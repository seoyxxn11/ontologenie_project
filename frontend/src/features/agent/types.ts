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

// Agent가 답변 중에 부른 Action의 결과(160). 실행했으면 실행 기록 상태, 실행하지 않았으면 CONFIRMATION_REQUIRED·NOT_RUN
export type AgentActionStatus =
  | 'SUCCEEDED'
  | 'RUNNING'
  | 'BLOCKED'
  | 'FAILED'
  | 'CONFIRMATION_REQUIRED'
  | 'NOT_RUN'

export type AgentAction = {
  code: string
  name: string
  status: AgentActionStatus
  executionId: number | null
  // 재배정 계획이면 planId·itemCount, 실패면 errorMessage
  result: Record<string, unknown>
  blockedReason: string | null
}

export type AnswerMeta = {
  stage?: AgentStage
  syncStatus?: SyncStatus
  results?: AgentResult[]
  actions?: AgentAction[]
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

// 첫 질문과 함께 보내는 참고 객체.
export type ReferenceObject = {
  id: number
  name: string
  typeName: string
}

export type AgentEntryState = {
  conversationId: number
  referenceObjectId: number
  referenceObjectName: string
  referenceObjectTypeName: string
  draft: string
}
