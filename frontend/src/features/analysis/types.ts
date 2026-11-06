export type AnalysisStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type AnalysisTarget = {
  sourceResourceId: number
  // 고른 컬럼. 없으면 그 표 전체
  columns?: string[]
}

// 017·019 분석 실행. resultSummary는 AI가 105로 보낸 요약이다.
export type Analysis = {
  id: number
  runKind: 'INITIAL' | 'REANALYSIS'
  status: AnalysisStatus
  targets: { sourceResourceId: number }[]
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  resultSummary: {
    proposals?: number
    holdRecommended?: number
    analyzedTables?: string[]
    documentLinks?: number
    // 고른 컬럼 중 원천에서 사라져 빼고 분석한 것 {"mes.work_order": ["컬럼"]}
    missingColumns?: Record<string, string[]>
    // 행이 많아 기본키 순서로 앞에서 자른 표 {"mes.sensor_reading": 100000}
    sampledTables?: Record<string, number>
  } | null
  errorMessage: string | null
  requestedBy: number
}

// 018 분석 실행 접수 응답
export type AnalysisRun = {
  id: number
  runKind: 'INITIAL' | 'REANALYSIS'
  status: AnalysisStatus
  targets: AnalysisTarget[]
  requestedAt: string
  // 같은 대상의 실행에 합류했는지
  joined: boolean
}
