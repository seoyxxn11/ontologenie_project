import { apiGet, apiPatch, apiPost } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'

export type Issue = {
  id: number
  issueKind: string
  status: string
  dedupeKey: string
  failureReason: string | null
  rawValue: string | null
  transformedValue: string | null
  evidence: Record<string, unknown>
  objectInstanceId: number | null
  linkMappingId: number | null
  proposalItemId: number | null
  detectedCheckRunId: number
  resolvedCheckRunId: number | null
  forwardedAt: string | null
  forwardedBy: number | null
  rowVersion: number
}

const ISSUE_PAGE_SIZE = 20

export function fetchIssues(
  filters: { status: string; issueKind: string },
  page: number,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({
    page: String(page),
    size: String(ISSUE_PAGE_SIZE),
  })
  if (filters.status) query.set('status', filters.status)
  if (filters.issueKind) query.set('issueKind', filters.issueKind)
  return apiGet<Issue[]>(`/issues?${query}`, { signal })
}

export function fetchIssue(id: number, signal?: AbortSignal) {
  return apiGet<Issue>(`/issues/${id}`, { signal })
}

export type IssueVerification = {
  issueId: number
  checkRunId: number
  triggerKind: string
  status: string
  outcome: 'PASSED' | 'FAILED'
  detail: Evidence & { linkMappingId?: number }
  checkedAt: string
}

type Evidence = {
  classification?: string
  reason?: string | null
  rawValue?: string | null
  transformedValue?: string | null
}

export type ReverificationComparison = {
  comparedToRunId: number
  coverage: 'PARTIAL' | 'UNAVAILABLE'
  hasBaselinePassEvidence: boolean
  rows: {
    category: 'RESOLVED' | 'REMAINING' | 'NEW'
    ruleCode: string
    objectInstanceId: number
    before: Evidence
    after: Evidence
  }[]
}

export function fetchIssueVerifications(
  id: number,
  page: number,
  signal?: AbortSignal,
) {
  return apiGet<IssueVerification[]>(
    `/issues/${id}/verifications?page=${page}&size=${ISSUE_PAGE_SIZE}`,
    { signal },
  )
}

// 070의 해결 통과 기록에서 실제 검사에 사용한 규칙 개정본을 찾는다.
export async function fetchResolutionRuleId(
  issueId: number,
  runId: number,
  signal?: AbortSignal,
): Promise<number | null> {
  for (let page = 0; ; page += 1) {
    const response = await fetchIssueVerifications(issueId, page, signal)
    if (
      !Array.isArray(response.data) ||
      !response.meta ||
      response.meta.page !== page ||
      typeof response.meta.hasNext !== 'boolean' ||
      (response.meta.hasNext && response.data.length === 0) ||
      response.data.some((record) => record.issueId !== issueId)
    ) {
      throw new ApiError('해결 근거의 판정 이력을 확인할 수 없습니다.', {
        kind: 'invalid-response',
      })
    }
    const record = response.data.find((item) => item.checkRunId === runId)
    if (record) {
      if (
        record.triggerKind !== 'REVERIFY' ||
        record.status !== 'SUCCEEDED' ||
        record.outcome !== 'PASSED'
      ) {
        throw new ApiError('해결에 사용한 통과 판정을 확인할 수 없습니다.', {
          kind: 'invalid-response',
        })
      }
      const ruleId = isRecord(record.detail)
        ? record.detail.linkMappingId
        : null
      return typeof ruleId === 'number' &&
        Number.isSafeInteger(ruleId) &&
        ruleId > 0
        ? ruleId
        : null
    }
    if (!response.meta.hasNext) return null
  }
}

export function fetchReverificationComparison(
  id: number,
  page: number,
  signal?: AbortSignal,
) {
  return apiGet<ReverificationComparison>(
    `/checks/runs/${id}/comparison?page=${page}&size=${ISSUE_PAGE_SIZE}`,
    { signal },
  )
}

export async function createIssueManualLink({
  issueId,
  ...request
}: {
  issueId: number
  targetObjectId: number
  reason: string
  rowVersion: number
}) {
  const response = await apiPost<{
    issueId: number
    linkId: number
    outcome: 'CREATED' | 'REACTIVATED'
    actionExecutionId: number
  }>(`/issues/${issueId}/manual-links`, request)
  const result = response?.data
  if (
    !result ||
    result.issueId !== issueId ||
    !Number.isSafeInteger(result.linkId) ||
    result.linkId <= 0 ||
    !Number.isSafeInteger(result.actionExecutionId) ||
    result.actionExecutionId <= 0 ||
    !['CREATED', 'REACTIVATED'].includes(result.outcome)
  ) {
    throw new ApiError('수동 연결 저장 결과를 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  }
  return response!
}

export function changeIssueStatus(
  id: number,
  request: {
    status: string
    reason: string
    rowVersion: number
    resolvedCheckRunId?: number
  },
) {
  return apiPatch<Issue>(`/issues/${id}/status`, request)
}

export type IssueHistoryEntry = {
  id: number
  issueId: number
  beforeStatus: string | null
  afterStatus: string
  changedAt: string
  changedBy: number | null
  checkRunId: number | null
  note: string | null
}

export const ISSUE_HISTORY_PAGE_SIZE = 20

// 068: 외부로 전송하지 않고 전달한 사실만 기록한다.
export async function recordIssueForward(id: number, rowVersion: number) {
  const response = await apiPost<Issue>(`/issues/${id}/forward`, { rowVersion })
  const issue = response?.data
  if (
    !response ||
    issue?.id !== id ||
    !Number.isSafeInteger(issue.rowVersion) ||
    issue.rowVersion <= rowVersion ||
    !issue.forwardedAt ||
    !Number.isFinite(Date.parse(issue.forwardedAt)) ||
    !Number.isSafeInteger(issue.forwardedBy) ||
    Number(issue.forwardedBy) < 1
  ) {
    throw new ApiError(
      '전달 기록 저장 결과를 확인할 수 없습니다. 최신 기록을 다시 불러와 주세요.',
      {
        kind: 'invalid-response',
      },
    )
  }
  return response
}

// 069: 서버가 제공하는 변경 시각·ID 오름차순을 유지한다.
export async function fetchIssueHistory(
  id: number,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(ISSUE_HISTORY_PAGE_SIZE),
  })
  const response = await apiGet<IssueHistoryEntry[]>(
    `/issues/${id}/history?${params}`,
    { signal },
  )
  const meta = response.meta
  if (
    !Array.isArray(response.data) ||
    response.data.some((entry) => entry.issueId !== id) ||
    !meta ||
    meta.page !== page ||
    meta.size !== ISSUE_HISTORY_PAGE_SIZE ||
    !Number.isSafeInteger(meta.totalElements) ||
    meta.totalElements < 0
  ) {
    throw new ApiError('상태 변경 이력 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  }
  return { data: response.data, meta }
}
