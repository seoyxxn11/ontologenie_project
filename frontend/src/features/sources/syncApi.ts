import { apiGet, apiPost } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { PageMeta } from '../../api/types'
import type { CheckStatus } from './types'

export type SourceSyncSummary = {
  latestRun: Pick<
    SyncRun,
    'syncRunId' | 'status' | 'triggerKind' | 'finishedAt'
  > | null
  cdcLastAppliedAt: string | null
}

// 074는 접수 시각 내림차순이며 기본 조회에서 CDC 실행을 제외한다.
// 최근 접수한 전체 읽기 실행과 CDC 반영 시각을 별도로 반환한다.
export async function fetchSourceSyncSummary(
  sourceId: number,
  signal?: AbortSignal,
): Promise<SourceSyncSummary> {
  const body = await apiGet<SyncRun[], NonNullable<SyncPage['meta']>>(
    `/sources/${sourceId}/sync-runs?page=0&size=1`,
    { signal },
  )
  const run = body.data?.[0]
  const meta = body.meta
  const validTime = (value: unknown) =>
    value == null ||
    (typeof value === 'string' && Number.isFinite(Date.parse(value)))
  if (
    !Array.isArray(body.data) ||
    body.data.length > 1 ||
    !meta ||
    meta.page !== 0 ||
    meta.size !== 1 ||
    !Number.isSafeInteger(meta.totalElements) ||
    meta.totalElements < body.data.length ||
    !validTime(meta.cdcLastAppliedAt) ||
    (run &&
      (run.dataSourceId !== sourceId ||
        !Number.isSafeInteger(run.syncRunId) ||
        run.syncRunId < 1 ||
        !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(run.status) ||
        !['MANUAL', 'SCHEDULED', 'ON_DEMAND', 'RECOVERY'].includes(
          run.triggerKind,
        ) ||
        !validTime(run.finishedAt))) ||
    (!run && meta.totalElements !== 0)
  ) {
    throw new ApiError('수집 요약 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  }
  return {
    latestRun: run ?? null,
    cdcLastAppliedAt: meta.cdcLastAppliedAt ?? null,
  }
}

export type SyncTrigger =
  'CDC' | 'MANUAL' | 'SCHEDULED' | 'ON_DEMAND' | 'RECOVERY'
export type SyncResource = {
  syncRunId: number
  dataSourceId: number
  sourceResourceId: number
  syncStrategy: 'CDC' | 'FULL_COMPARE'
  mode: 'FULL' | 'INCREMENTAL'
  status: CheckStatus | 'SKIPPED'
  fromLsn: number | null
  toLsn: number | null
  rowsRead: number
  rowsUpserted: number
  rowsDeleted: number
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
}
export type SyncRun = {
  id: number
  syncRunId: number
  dataSourceId: number
  triggerKind: SyncTrigger
  status: CheckStatus
  requestedBy: number | null
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  resources: SyncResource[]
}
export type SyncAccepted = {
  syncRunId: number | null
  dataSourceId: number
  triggerKind: 'MANUAL'
  status: CheckStatus | null
  requestedBy: number
  requestedAt: string
  joinedExisting: boolean
  resources: (SyncResource & { joined: boolean })[]
}
export type SyncFilters = {
  page: number
  status?: CheckStatus
  triggerKind?: SyncTrigger
  from?: string
  to?: string
}
export type SyncPage = {
  data: SyncRun[]
  meta?: PageMeta & { cdcLastAppliedAt?: string | null }
}

export const SYNC_RUN_PAGE_SIZE = 10
export const SYNC_ACTIVE_POLL_MS = 5000
export const SYNC_IDLE_POLL_MS = 30000

// 073~075: 수동 다시 읽기 접수·수집 이력·실행 상세.
export const sourceSyncApi = {
  async request(sourceId: number, sourceResourceIds?: number[]) {
    const body = await apiPost<SyncAccepted>(
      `/sources/${sourceId}/sync-runs`,
      sourceResourceIds ? { sourceResourceIds } : {},
    )
    if (!body?.data)
      throw new ApiError(
        '수집 접수 결과를 확인할 수 없습니다. 이력을 확인해 주세요.',
        { kind: 'invalid-response' },
      )
    return body.data
  },
  list(sourceId: number, filters: SyncFilters): Promise<SyncPage> {
    const params = new URLSearchParams({
      page: String(filters.page),
      size: String(SYNC_RUN_PAGE_SIZE),
    })
    for (const key of ['status', 'triggerKind', 'from', 'to'] as const) {
      if (filters[key]) params.set(key, filters[key])
    }
    return apiGet<SyncRun[], NonNullable<SyncPage['meta']>>(
      `/sources/${sourceId}/sync-runs?${params}`,
    )
  },
  async detail(id: number) {
    return (await apiGet<SyncRun>(`/sync-runs/${id}`)).data
  },
}
export type SourceSyncApi = typeof sourceSyncApi
