import type { PageMeta } from '../../api/types'

export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'BLOCKED' | 'FAILED'

// 096이 이번 조회를 전체(ALL)로 했는지 본인 실행(MINE)으로 했는지
export type ExecutionScope = 'ALL' | 'MINE'

// 080·096 응답의 meta. scope는 실행 이력 화면을 하나로 합치면서 백엔드에 요청할 값이다.
export type ExecutionPageMeta = PageMeta & {
  scope?: ExecutionScope
}

// 080·096 실행 하나가 바꾼 행의 전후 내용
export type EntityChange = {
  tableName: string
  rowKey: Record<string, unknown>
  changeKind: 'INSERT' | 'UPDATE' | 'DELETE'
  beforeData?: Record<string, unknown> | null
  afterData?: Record<string, unknown> | null
}

// 080 객체 관련 실행 이력·096 실행·감사 이력 응답의 실행 하나
export type Execution = {
  id: number
  requestId: string
  eventKind:
    | 'ACTION'
    | 'SYNC_RUN'
    | 'CHECK_RUN'
    | 'ANALYSIS_RUN'
    | 'SOURCE_CHECK'
    | 'SOURCE_PREVIEW'
    | 'HOLD_AUTO_RESOLVE'
  actionDefinitionId?: number | null
  actorType: 'USER' | 'AGENT' | 'SYSTEM'
  actorId?: number | null
  actorRoleCode?: string | null
  checkedPermissionCode?: string | null
  entryPoint: 'WEB' | 'AGENT' | 'BATCH' | 'SYSTEM' | 'INTERNAL'
  targetType?: string | null
  targetId?: number | null
  input: Record<string, unknown>
  status: ExecutionStatus
  blockedReason?: string | null
  result: Record<string, unknown>
  requestedAt: string
  finishedAt?: string | null
  entityChanges: EntityChange[]
}
