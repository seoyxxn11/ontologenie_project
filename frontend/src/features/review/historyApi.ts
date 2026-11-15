import { apiGet, ApiError } from '../../api/client'
import { isRecord } from '../../api/errors'

export type HistoricalRevision = {
  revision: number
  payload: Record<string, unknown>
  evidence: Record<string, unknown>
  aiConfidence: number | null
  createdBy: number | null
  createdAt: string
}

export async function fetchHistoricalRevision(
  id: number,
  revision: number,
  signal?: AbortSignal,
) {
  let page = 0
  while (true) {
    const response = await apiGet<HistoricalRevision[]>(
      `/proposals/${id}/revisions?page=${page}&size=20`,
      { signal },
    )
    if (
      !Array.isArray(response.data) ||
      !response.meta ||
      response.meta.page !== page ||
      response.meta.size !== 20
    ) {
      throw new ApiError('과거 개정 조회 응답을 확인할 수 없습니다.', {
        kind: 'invalid-response',
      })
    }
    const found = response.data.find((item) => item.revision === revision)
    if (found) {
      if (!isRecord(found.payload) || !isRecord(found.evidence))
        throw new ApiError('과거 개정의 내용을 확인할 수 없습니다.', {
          kind: 'invalid-response',
        })
      return found
    }
    if (!response.meta.hasNext || response.data.length === 0) break
    page += 1
    if (page * 20 >= response.meta.totalElements) break
  }
  throw new ApiError('해당 결정 당시의 개정을 찾을 수 없습니다.', {
    kind: 'invalid-response',
  })
}
