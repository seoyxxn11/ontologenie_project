import { apiGet, apiPost, ApiError } from '../../api/client'
import { PROPOSAL_PAGE_SIZE } from '../review/api'
import type { Proposal, ProposalFilters } from '../review/types'
import type { Analysis, AnalysisRun, AnalysisTarget } from './types'

export const RECENT_ANALYSIS_COUNT = 5

// 018 선택 범위 AI 분석 실행
export async function startAnalysis(targets: AnalysisTarget[]) {
  const body = await apiPost<AnalysisRun>('/analyses', {
    runKind: 'INITIAL',
    targets,
  })
  if (!body?.data || !Number.isSafeInteger(body.data.id)) {
    throw new ApiError('분석 접수 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  }
  return body.data
}

// 019 분석 실행 상태·결과 조회
export async function fetchAnalysis(id: number, signal?: AbortSignal) {
  return (await apiGet<Analysis>(`/analyses/${id}`, { signal })).data
}

// 017 분석 실행 목록 (최근 것부터)
export async function fetchRecentAnalyses(signal?: AbortSignal) {
  return (
    await apiGet<Analysis[]>(`/analyses?page=0&size=${RECENT_ANALYSIS_COUNT}`, {
      signal,
    })
  ).data
}

// 020 분석에서 생성된 제안. 출처는 늘 AI라서 origin 조건은 보내지 않는다.
export function fetchAnalysisProposals(
  id: number,
  filters: ProposalFilters,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PROPOSAL_PAGE_SIZE),
  })
  for (const [key, value] of Object.entries(filters)) {
    if (value && key !== 'origin') params.set(key, value)
  }
  return apiGet<Proposal[]>(`/analyses/${id}/proposals?${params}`, { signal })
}
