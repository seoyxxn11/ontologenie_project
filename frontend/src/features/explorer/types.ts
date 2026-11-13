export type ObjectStatus = 'ACTIVE' | 'DELETED_AT_SOURCE'

export type ObjectTypeOption = {
  id: number
  code: string
  name: string
}

export type ObjectPropertyDefinition = {
  id: number
  code: string
  name: string
  unit?: string | null
  description?: string | null
  dataType:
    | 'STRING'
    | 'INTEGER'
    | 'DECIMAL'
    | 'BOOLEAN'
    | 'DATE'
    | 'TIME'
    | 'DATETIME'
    | 'JSON'
}

// 076 권한 범위 객체 검색 응답의 객체 하나
export type ObjectInstance = {
  id: number
  objectTypeId: number
  objectTypeName: string
  displayName: string
  properties: Record<string, unknown>
  restrictedPropertyCodes: string[]
  status: ObjectStatus
  sourceMappingId?: number | null
  sourceKey?: Record<string, unknown> | null
  firstCollectedAt: string
  lastSyncedAt?: string | null
  dataAsOf?: string | null
}

// 077 객체 상세 조회 응답: 목록 필드 + 속성 표시 이름 + 원천 위치
export type ObjectInstanceDetail = ObjectInstance & {
  propertyNames: Record<string, string>
  sourceSystemName?: string | null
  sourceResourceKey?: string | null
}

export type LinkOrigin = 'RULE' | 'DOCUMENT' | 'MANUAL'

export type LinkComparison = {
  sequenceNo: number
  sourceProperty: string
  sourceColumn?: string
  sourceValue: string
  targetProperty: string
  targetColumn?: string
  targetValue: string
  matchedValue: string
}

// 078 연결 근거. RULE은 원천 키와 비교 조건, DOCUMENT는 원문 구간·사건·시간 표현을 제공한다.
export type LinkEvidence = {
  evidenceRestricted?: boolean
  sourceKey?: Record<string, unknown>
  rawValue?: unknown
  transformedValue?: unknown
  comparisons?: LinkComparison[]
  quote?: string
  event?: string
  timeExpression?: string
}

// 078 확정 관계·원천 근거 조회 응답의 연결 하나
export type ObjectLink = {
  id: number
  linkTypeId: number
  linkTypeName: string
  sourceObjectId: number
  targetObjectId: number
  relatedObject: {
    id: number
    displayName: string
    objectTypeId: number
    objectTypeName: string
  }
  origin: LinkOrigin
  status: 'ACTIVE'
  linkMappingId?: number | null
  evidence: LinkEvidence
  expressionKind?: 'OBSERVED' | 'ESTIMATED' | null
  confirmedBy?: number | null
}

export type LinkDirection = 'OUT' | 'IN'

// 객체의 활성 관계를 관계 유형·방향별로 묶은 건수 (link-groups 응답 한 줄)
export type ObjectLinkGroup = {
  linkTypeId: number
  linkTypeName: string
  direction: LinkDirection
  relatedObjectTypeId: number
  relatedObjectTypeName: string
  linkCount: number
}

export type Cardinality =
  'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'MANY_TO_MANY'

// 042 객체 유형 관계도의 노드 하나 (객체 유형)
export type ModelNode = {
  id: number
  code: string
  name: string
}

// 042 객체 유형 관계도의 선 하나 (관계 유형)
export type ModelEdge = {
  id: number
  code: string
  name: string
  sourceTypeId: number
  targetTypeId: number
  cardinality: Cardinality
  // 비활성 연결을 포함한 전체 연결 수. 042 API는 ADMIN 전용이다.
  relationCount: number
  activeLinkCount: number
}

// 042 객체 유형 관계도 응답
export type ModelGraph = {
  nodes: ModelNode[]
  edges: ModelEdge[]
}
