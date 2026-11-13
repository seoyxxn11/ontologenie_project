import { apiGet, apiPost } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'
import type { SampleCell, SourceSampleAccess } from './samplePreview'
import type { CheckStatus } from './types'

const positiveId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const timestamp = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))

function invalidResponse(): never {
  throw new ApiError('표본 조회 응답을 확인할 수 없습니다.', {
    kind: 'invalid-response',
  })
}

function cell(value: unknown): SampleCell {
  if (
    value === null ||
    typeof value === 'string' ||
    typeof value === 'boolean' ||
    (typeof value === 'number' && Number.isFinite(value))
  )
    return { kind: 'visible', value }
  if (Array.isArray(value) || isRecord(value))
    return { kind: 'visible', value: JSON.stringify(value) }
  return invalidResponse()
}

// 391의 분류·원천 읽기 권한은 서버가 적용한다. 제외된 값을 복원하거나 추가 조회하지 않는다.
export const sourceSampleApi: SourceSampleAccess = {
  policyDescription:
    '수집 계정의 읽기 권한과 서버 원천 데이터 정책을 적용한 표본입니다. 금지·민감 컬럼은 제외하며 서버가 제공한 컬럼과 값만 표시합니다.',
  async request(sourceId, resourceId, rowLimit) {
    if (
      !positiveId(sourceId) ||
      !positiveId(resourceId) ||
      !Number.isInteger(rowLimit) ||
      rowLimit < 1 ||
      rowLimit > 20
    )
      throw new Error('원천 테이블과 표본 행 수(1~20)를 확인해 주세요.')
    const response = await apiPost<unknown>(
      `/sources/${sourceId}/resources/${resourceId}/samples`,
      { rowLimit },
    )
    const data = response?.data
    if (
      !isRecord(data) ||
      !positiveId(data.previewId) ||
      !['QUEUED', 'RUNNING'].includes(String(data.status)) ||
      typeof data.joined !== 'boolean' ||
      !timestamp(data.requestedAt) ||
      !timestamp(data.expiresAt) ||
      Date.parse(data.expiresAt) <= Date.parse(data.requestedAt)
    )
      return invalidResponse()
    return {
      previewId: data.previewId,
      joined: data.joined,
      expiresAt: data.expiresAt,
    }
  },
  async read(previewId, resourceId) {
    const { data } = await apiGet<unknown>(
      `/ontology/source-previews/${previewId}`,
    )
    if (
      !isRecord(data) ||
      data.id !== previewId ||
      data.previewKind !== 'SAMPLE' ||
      !positiveId(data.sourceResourceId) ||
      (resourceId !== undefined && data.sourceResourceId !== resourceId) ||
      !timestamp(data.expiresAt) ||
      !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(
        String(data.status),
      )
    )
      return invalidResponse()
    if (Date.parse(data.expiresAt) <= Date.now())
      throw new ApiError('표본이 만료되었습니다.', {
        kind: 'not-found',
        status: 404,
      })
    const status = data.status as CheckStatus
    if (status !== 'SUCCEEDED') return { status, columns: [], rows: [] }
    const result = data.result
    if (
      !isRecord(result) ||
      !Array.isArray(result.columns) ||
      !result.columns.every((column) => typeof column === 'string') ||
      !Array.isArray(result.rows) ||
      result.rows.length > 20
    )
      return invalidResponse()
    const columns = result.columns as string[]
    return {
      status,
      columns,
      rows: result.rows.map((row) => {
        if (!Array.isArray(row) || row.length !== columns.length)
          return invalidResponse()
        return row.map(cell)
      }),
    }
  },
}
