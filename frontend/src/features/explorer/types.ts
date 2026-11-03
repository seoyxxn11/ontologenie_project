export type ObjectStatus = 'ACTIVE' | 'DELETED_AT_SOURCE'

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

// 078 연결 근거: RULE은 원천 키·변환 결과, DOCUMENT는 원문 구간·사건·시간 표현
export type LinkEvidence = {
  sourceKey?: Record<string, unknown>
  rawValue?: unknown
  transformedValue?: unknown
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

export type Cardinality =
  'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'MANY_TO_MANY'

// 042 객체 유형 관계도의 노드 하나 (객체 유형)
export type ModelNode = {
  id: number
  code: string
  name: string
  // 유형별 객체 수. 업무 데이터 화면에서 쓰려고 백엔드에 추가를 요청할 값이라 아직 없을 수 있다.
  objectCount?: number
}

// 042 객체 유형 관계도의 선 하나 (관계 유형)
export type ModelEdge = {
  id: number
  code: string
  name: string
  sourceTypeId: number
  targetTypeId: number
  cardinality: Cardinality
  // INACTIVE까지 센 운영용 숫자라 ADMIN만 받고, 다른 역할은 null이다.
  relationCount?: number | null
  activeLinkCount: number
}

// 042 객체 유형 관계도 응답
export type ModelGraph = {
  nodes: ModelNode[]
  edges: ModelEdge[]
}
