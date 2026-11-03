export type SourceKind = 'POSTGRES' | 'CSV' | 'DOCUMENT'

export type ConnectionStatus = 'UNVERIFIED' | 'CONNECTED' | 'FAILED'

// 009 원천 목록의 한 건. 비밀값은 응답에 없다.
export type Source = {
  id: number
  code: string
  name: string
  sourceKind: SourceKind
  connectionStatus: ConnectionStatus
  lastCheckedAt: string | null
  syncEnabled: boolean
  syncIntervalMinutes?: number | null
  rowVersion: number
  connectionConfig: {
    host?: string | null
    port?: number | null
    database?: string | null
  }
}

export type CheckStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

// 011 원천 상세. 계정은 이름만 오고 비밀번호는 없다.
export type SourceDetail = Source & {
  credentials: { purpose: AccountPurpose; username: string }[]
  latestConnectionCheck: {
    checkId: number
    status: CheckStatus
    finishedAt: string | null
    errorMessage: string | null
  } | null
}

// 013 연결 검사 접수 응답
export type ConnectionCheckRequest = {
  checkId: number
  status: CheckStatus
  joined: boolean
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
}

export type AccountPurpose = 'SYNC' | 'AI'

export type AccountCheck = {
  purpose: AccountPurpose
  status: 'SUCCEEDED' | 'FAILED'
  readableTableCount: number | null
  readableColumnCount: number | null
  errorMessage: string | null
}

// 100 연결 검사 결과
export type ConnectionCheck = {
  checkId: number
  dataSourceId: number
  status: CheckStatus
  requestedBy: number
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  result: { accounts: AccountCheck[] }
}

export type ResourceKind = 'TABLE' | 'VIEW' | 'DOCUMENT_SET' | 'FILE'

// 014 원천 표·뷰·자료 목록의 한 건
export type SourceResource = {
  id: number
  dataSourceId: number
  resourceKey: string
  resourceKind: ResourceKind
  isReadable: boolean
  sourceMetadata: { description?: string } & Record<string, unknown>
}

// 015 자원의 컬럼
export type SourceColumn = {
  id: number
  sourceResourceId: number
  columnName: string
  sourceDataType: string
  isNullable: boolean
  isKeyPart: boolean
  isPresent: boolean
  ordinalPosition: number
}

export type CredentialInput = {
  purpose: AccountPurpose
  username: string
  password: string
}

// 010 원천 등록 요청
export type SourceCreateRequest = {
  code: string
  name: string
  sourceKind: SourceKind
  connectionConfig: Source['connectionConfig']
  credentials: CredentialInput[]
}

// 012 원천 정보 변경 요청. 바꿀 값만 보낸다.
export type SourceUpdateRequest = {
  rowVersion: number
  name?: string
  connectionConfig?: Source['connectionConfig']
  credentials?: CredentialInput[]
}
