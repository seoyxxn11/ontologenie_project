import { apiGet, apiPost, ApiError } from '../../api/client'
import type {
  Proposal,
  ProposalFilters,
  ProposalOption,
  ProposalWriteResult,
} from './types'

// 화면 목록 한 쪽에 보여 주는 후보 수
export const PROPOSAL_PAGE_SIZE = 20
// 목록·집계용으로 서버에서 한 번에 받는 후보 수(서버 제안 목록은 크기 상한이 없다)
export const PROPOSAL_FETCH_SIZE = 100

export function fetchProposals(
  filters: ProposalFilters,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PROPOSAL_FETCH_SIZE),
  })
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value)
  }
  return apiGet<Proposal[]>(`/proposals?${params}`, { signal })
}

export async function fetchProposal(id: number, signal?: AbortSignal) {
  return (await apiGet<Proposal>(`/proposals/${id}`, { signal })).data
}

export async function fetchProposalOptions(
  id: number,
  revision: number,
  signal?: AbortSignal,
) {
  const response = await apiGet<ProposalOption[]>(
    `/proposals/${id}/options?revision=${revision}`,
    { signal },
  )
  if (response.data.some((option) => option.revision !== revision))
    throw new ApiError(
      '후보 개정이 일치하지 않습니다. 제안을 새로 조회해 주세요.',
      { kind: 'conflict', status: 409 },
    )
  return response.data
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

// 객체 유형 후보의 조회 그룹·속성 민감도를 고쳐 개정본으로 저장한다(392). 정의 후보라 후보 검사는 접수하지 않는다(checkRunId null).
// 선택지는 그대로 다시 보낸다. 근거는 바꾸지 않는다.
export async function reviseObjectProposal(
  proposal: Proposal,
  payload: Record<string, unknown>,
) {
  const options = await fetchProposalOptions(proposal.id, proposal.revision)
  const response = await apiPost<ProposalWriteResult>(
    `/proposals/${proposal.id}/revisions`,
    {
      baseRevision: proposal.revision,
      payload,
      evidence: proposal.evidence,
      options: options.map((option) => ({
        payload: option.payload,
        evidence: option.evidence,
      })),
    },
  )
  if (
    response?.data?.id !== proposal.id ||
    !Number.isSafeInteger(response.data.revision) ||
    response.data.revision <= proposal.revision
  )
    throw new ApiError(
      '저장 응답을 확인할 수 없습니다. 제안을 새로 조회해 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}
