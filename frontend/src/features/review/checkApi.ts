import { apiGet, ApiError } from '../../api/client'

export type CheckResult = {
  id: number
  checkRunId: number
  proposalItemId: number | null
  proposalItemRevision: number | null
  linkMappingId: number | null
  previousResultId: number | null
  totalCount: number
  matchedCount: number
  unmatchedCount: number
  duplicateCount: number
  violationCount: number
  matchRate: number | null
  sampleRows: Record<string, unknown>[]
}
export const CHECK_PAGE_SIZE = 20

export async function fetchCheckResults(
  runId: number,
  proposalId: number,
  revision: number,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    proposalItemId: String(proposalId),
    page: String(page),
    size: String(CHECK_PAGE_SIZE),
  })
  const response = await apiGet<CheckResult[]>(
    `/checks/runs/${runId}/results?${params}`,
    { signal },
  )
  if (
    !response.meta ||
    response.meta.page !== page ||
    !Array.isArray(response.data)
  )
    throw new ApiError('검사 결과의 페이지 정보를 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  if (
    response.data.some(
      (row) =>
        row.checkRunId !== runId ||
        row.proposalItemId !== proposalId ||
        row.proposalItemRevision !== revision,
    )
  )
    throw new ApiError(
      '검사 결과의 제안·개정이 일치하지 않습니다. 최신 제안을 다시 조회해 주세요.',
      { kind: 'conflict', status: 409 },
    )
  return { data: response.data, meta: response.meta }
}
