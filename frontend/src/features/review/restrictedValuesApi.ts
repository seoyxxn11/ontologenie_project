import { apiGet, apiPost } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'

// 제안 검토 때 제한 컬럼의 실제 값 확인(447). 서버가 대상(제안이 가리키는 제한 컬럼)과 권한을 정하고,
// 결과는 한 번 읽으면 서버가 지운다. 화면은 받은 값을 이 화면에서만 보여 주고 저장하지 않는다.

export type RestrictedColumn = {
  sourceResourceId: number
  sourceName: string | null
  resourceKey: string
  column: string
}

export type RestrictedValueResult =
  | { status: 'QUEUED' | 'RUNNING' }
  | { status: 'FAILED'; errorMessage: string }
  | {
      status: 'SUCCEEDED'
      values: string[]
      totalCount: number
      emptyCount: number
      distinctCount: number
    }

const positiveId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const count = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0

function invalidResponse(): never {
  throw new ApiError('값 확인 응답을 확인할 수 없습니다.', {
    kind: 'invalid-response',
  })
}

export async function fetchRestrictedColumns(
  proposalId: number,
  signal?: AbortSignal,
): Promise<RestrictedColumn[]> {
  const { data } = await apiGet<unknown>(
    `/proposals/${proposalId}/restricted-columns`,
    { signal },
  )
  if (!Array.isArray(data)) return invalidResponse()
  return data.map((row) => {
    if (
      !isRecord(row) ||
      !positiveId(row.sourceResourceId) ||
      typeof row.resourceKey !== 'string' ||
      typeof row.column !== 'string'
    )
      return invalidResponse()
    return {
      sourceResourceId: row.sourceResourceId,
      sourceName: typeof row.sourceName === 'string' ? row.sourceName : null,
      resourceKey: row.resourceKey,
      column: row.column,
    }
  })
}

export async function requestRestrictedValues(
  proposalId: number,
  column: RestrictedColumn,
): Promise<number> {
  const response = await apiPost<unknown>(
    `/proposals/${proposalId}/restricted-values`,
    { sourceResourceId: column.sourceResourceId, column: column.column },
  )
  const data = response?.data
  if (!isRecord(data) || !positiveId(data.requestId)) return invalidResponse()
  return data.requestId
}

// 결과 읽기는 POST다. 끝난 결과를 읽으면 서버가 열람 기록을 남기고 지우기 때문이다.
export async function readRestrictedValues(
  proposalId: number,
  requestId: number,
): Promise<RestrictedValueResult> {
  const response = await apiPost<unknown>(
    `/proposals/${proposalId}/restricted-values/${requestId}/result`,
  )
  const data = response?.data
  if (!isRecord(data) || data.requestId !== requestId) return invalidResponse()
  if (data.status === 'QUEUED' || data.status === 'RUNNING')
    return { status: data.status }
  if (data.status === 'FAILED')
    return {
      status: 'FAILED',
      errorMessage:
        typeof data.errorMessage === 'string' && data.errorMessage.trim()
          ? data.errorMessage
          : '값을 확인하지 못했습니다.',
    }
  if (
    data.status !== 'SUCCEEDED' ||
    !Array.isArray(data.values) ||
    !data.values.every((value) => typeof value === 'string') ||
    !count(data.totalCount) ||
    !count(data.emptyCount) ||
    !count(data.distinctCount)
  )
    return invalidResponse()
  return {
    status: 'SUCCEEDED',
    values: data.values as string[],
    totalCount: data.totalCount,
    emptyCount: data.emptyCount,
    distinctCount: data.distinctCount,
  }
}
