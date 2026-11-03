import { apiGet } from '../../api/client'
import type { Proposal, ProposalFilters } from './types'

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
