import { apiGet, apiPost, ApiError } from '../../api/client'
import type {
  Proposal,
  ProposalFilters,
  ProposalOption,
  ProposalWriteResult,
} from './types'

export const PROPOSAL_PAGE_SIZE = 20

export function fetchProposals(
  filters: ProposalFilters,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PROPOSAL_PAGE_SIZE),
  })
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value)
  }
  return apiGet<Proposal[]>(`/proposals?${params}`, { signal })
}

export async function fetchProposal(id: number, signal?: AbortSignal) {
  return (await apiGet<Proposal>(`/proposals/${id}`, { signal })).data
}

export async function fetchRuleForEdit(id: number, signal?: AbortSignal) {
  const proposal = await fetchProposal(id, signal)
  const options = await apiGet<ProposalOption[]>(
    `/proposals/${id}/options?revision=${proposal.revision}`,
    { signal },
  )
  return { proposal, options: options.data }
}

export async function saveRule(input: {
  id?: number
  baseRevision?: number
  payload: Record<string, unknown>
  evidence: Record<string, unknown>
  options?: ProposalOption[]
}) {
  const { id, payload, evidence } = input
  const response =
    id === undefined
      ? await apiPost<ProposalWriteResult>('/proposals/manual', {
          itemKind: 'LINK_RULE',
          payload,
          evidence,
        })
      : await apiPost<ProposalWriteResult>(`/proposals/${id}/revisions`, {
          baseRevision: input.baseRevision,
          payload,
          evidence,
          options: (input.options ?? []).map((option) => ({
            payload: option.payload,
            evidence: option.evidence,
          })),
        })
  if (
    !response?.data ||
    !Number.isSafeInteger(response.data.id) ||
    !Number.isSafeInteger(response.data.checkRunId) ||
    response.data.checkRunId < 1 ||
    !Number.isSafeInteger(response.data.revision)
  )
    throw new ApiError(
      '저장 응답을 확인할 수 없습니다. 목록에서 저장 여부를 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}
