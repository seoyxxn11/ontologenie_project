import type { EntityChange } from '../audit/types'

// 044 정의 변경 이력의 한 줄. 바뀐 정의 행의 전후와 그 변경을 실행한 사람·시각.
export type DefinitionChange = EntityChange & {
  id: number
  actionExecutionId: number
  actorId?: number | null
  actorType: 'USER' | 'AGENT' | 'SYSTEM'
  requestedAt: string
  actionCode?: string | null
}

export type RuleRevisionStatus = 'ACTIVE' | 'RETIRED'

export type RuleOrigin = 'AI_PROPOSAL' | 'MANUAL' | 'SEED'

// 046·047 규칙 개정본의 비교 조건 하나. 조건끼리는 AND로 묶인다.
export type RuleCondition = {
  sequenceNo: number
  sourcePropertyId: number
  targetPropertyId: number
  // 속성 이름은 개정 당시가 아니라 현재 이름이다. (백엔드 추가 예정이라 아직 없을 수 있다)
  sourcePropertyName?: string | null
  targetPropertyName?: string | null
  operator: 'EQ'
  sourceTransforms: Record<string, unknown>[]
  targetTransforms: Record<string, unknown>[]
}

// 046 연결 규칙 개정 이력 응답의 개정본 하나 (047 개정본 상세와 같은 모양)
export type RuleRevision = {
  id: number
  code: string
  revision: number
  linkTypeId: number
  // 관계 유형의 현재 이름 (백엔드 추가 예정이라 아직 없을 수 있다)
  linkTypeName?: string | null
  status: RuleRevisionStatus
  conditions: RuleCondition[]
  sourceFilter: Record<string, unknown>
  targetFilter: Record<string, unknown>
  retiredAt?: string | null
  description?: string | null
  createdAt: string
  createdBy?: number | null
  origin: RuleOrigin
  proposalItemId?: number | null
}

// 규칙 목록 API(백엔드 신설 예정: GET /ontology/rules)의 한 줄. 규칙 코드별 최신 개정본 요약.
export type RuleSummary = {
  code: string
  revision: number
  linkTypeId: number
  linkTypeName: string
  status: RuleRevisionStatus
  description?: string | null
}
