import { apiGet, ApiError } from '../../api/client'

export type ProposalDecisionRecord = {
  id: number
  proposalItemId: number
  itemRevision: number
  decision: string
  selectedOptionId: number | null
  reason: string
  decidedBy: number
  decidedAt: string
}

export const DECISION_PAGE_SIZE = 20

export async function fetchProposalDecisions(
  proposalId: number,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(DECISION_PAGE_SIZE),
  })
  const response = await apiGet<ProposalDecisionRecord[]>(
    `/proposals/${proposalId}/decisions?${params}`,
    { signal },
  )
  const meta = response.meta
  if (
    !Array.isArray(response.data) ||
    !meta ||
    meta.page !== page ||
    meta.size !== DECISION_PAGE_SIZE ||
    !Number.isSafeInteger(meta.totalElements) ||
    meta.totalElements < 0 ||
    response.data.some((record) => record.proposalItemId !== proposalId)
  )
    throw new ApiError('결정 이력의 제안·페이지 정보를 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return { data: response.data, meta }
}
