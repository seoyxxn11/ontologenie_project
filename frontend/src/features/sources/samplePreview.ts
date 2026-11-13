import type { CheckStatus } from './types'

// 016·102 응답을 sampleApi 어댑터에서 화면 모델로 변환한다.
// 가린 셀에는 원문을 전달하지 않는다.
export type SampleCell =
  | { kind: 'visible'; value: string | number | boolean | null }
  | { kind: 'masked' }
  | { kind: 'unavailable' }

export type SampleReceipt = {
  previewId: number
  joined: boolean
  expiresAt: string
}

export type SamplePreview = {
  status: CheckStatus
  columns: string[]
  rows: SampleCell[][]
}

export type SourceSampleAccess = {
  policyDescription: string
  request: (
    sourceId: number,
    resourceId: number,
    rowLimit: number,
  ) => Promise<SampleReceipt>
  read: (previewId: number, resourceId?: number) => Promise<SamplePreview>
}
