import { formatKstDateTime } from '../../utils/dateTime'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { SYNC_RUN_PAGE_SIZE } from './syncApi'
import useSyncPollingQuery from './useSyncPollingQuery'
import './sources.css'
import { fetchAllResources } from './api'
import type {
  SourceSyncApi,
  SyncAccepted,
  SyncFilters,
  SyncRun,
  SyncTrigger,
} from './syncApi'
import type { CheckStatus } from './types'

const statuses: Record<CheckStatus | 'SKIPPED', string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
  SKIPPED: '건너뜀',
}
const triggers: Record<SyncTrigger, string> = {
  CDC: 'CDC 변경 반영',
  MANUAL: '수동 수집',
  SCHEDULED: '예약 수집',
  ON_DEMAND: '질문 요청',
  RECOVERY: '복구 수집',
}
const active = (status?: string) => status === 'QUEUED' || status === 'RUNNING'
const time = (value: string | null | undefined) =>
  value ? formatKstDateTime(value) : '—'

function RunDetail({
  id,
  api,
  resourceLabel,
}: {
  id: number
  api: SourceSyncApi
  resourceLabel: (id: number) => string
}) {
  const { query, unavailable, transientError, refreshDelayed } =
    useSyncPollingQuery({
      queryKey: ['source-sync-run', id],
      queryFn: () => api.detail(id),
      isActive: (data) => active(data?.status),
      pollWhenIdle: false,
    })
  if (query.isPending)
    return <LoadingState message="수집 상세를 불러오는 중…" />
  if (!query.data || unavailable)
    return (
      <ErrorState
        error={query.error!}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  const run = query.data
  return (
    <section
      aria-label={`수집 실행 #${id} 상세`}
      className="space-y-3 rounded-lg border border-slate-200 p-4"
    >
      <h4 className="font-semibold">
        수집 실행 #{id} · {statuses[run.status]}
      </h4>
      <p>
        {triggers[run.triggerKind]} · 요청자{' '}
        {run.requestedBy == null ? '시스템' : `계정 #${run.requestedBy}`}
      </p>
      <p>
        접수 {time(run.requestedAt)} · 시작 {time(run.startedAt)} · 종료{' '}
        {time(run.finishedAt)}
      </p>
      {run.errorMessage && (
        <p
          role="alert"
          className="whitespace-pre-wrap break-words text-red-700"
        >
          {run.errorMessage}
        </p>
      )}
      <p className="text-slate-500">
        테이블 상태와 실행 상태는 별도로 기록됩니다. 실행 진행 상황은 위 상태를
        확인하세요.
      </p>
      <ul aria-label="테이블별 수집 결과" className="space-y-2">
        {run.resources.map((resource) => (
          <li
            key={resource.sourceResourceId}
            className="rounded bg-slate-50 p-3"
          >
            <p>
              {resourceLabel(resource.sourceResourceId)} ·{' '}
              {resource.syncStrategy} ·{' '}
              {resource.mode === 'FULL' ? '전체 읽기' : '변경분 반영'} ·{' '}
              {statuses[resource.status]}
            </p>
            <p>
              읽음 {resource.rowsRead} · 생성·갱신 {resource.rowsUpserted} ·
              삭제 {resource.rowsDeleted}
            </p>
            <p>
              시작 {time(resource.startedAt)} · 종료 {time(resource.finishedAt)}{' '}
              · LSN {resource.fromLsn ?? '—'} → {resource.toLsn ?? '—'}
            </p>
            {resource.errorMessage && (
              <p className="whitespace-pre-wrap break-words text-red-700">
                {resource.errorMessage}
              </p>
            )}
          </li>
        ))}
      </ul>
      {query.isError && !transientError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}
      {refreshDelayed && (
        <p role="status">
          상세 갱신이 지연되고 있습니다. 기존 내용을 유지하며 자동으로 다시
          확인합니다.
        </p>
      )}
    </section>
  )
}

export default function SourceSyncRuns({
  sourceId,
  api,
}: {
  sourceId: number
  api?: SourceSyncApi
}) {
  const client = useQueryClient()
  const inFlight = useRef(false)
  const [all, setAll] = useState(true)
  const [ids, setIds] = useState<number[]>([])
  const [filters, setFilters] = useState<SyncFilters>({ page: 0 })
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [accepted, setAccepted] = useState<SyncAccepted>()
  const [selected, setSelected] = useState<number>()
  const resources = useQuery({
    queryKey: ['sources', sourceId, 'resources', 'all'],
    queryFn: () => fetchAllResources(sourceId),
  })
  const {
    query: history,
    unavailable: historyUnavailable,
    transientError: historyTransient,
    refreshDelayed: historyDelayed,
  } = useSyncPollingQuery({
    queryKey: ['sources', sourceId, 'sync-runs', filters],
    queryFn: () => api!.list(sourceId, filters),
    enabled: !!api,
    isActive: (data) => data?.data.some((run) => active(run.status)) ?? false,
  })
  const request = useMutation({
    mutationFn: () => api!.request(sourceId, all ? undefined : ids),
    retry: false,
    onMutate: () => {
      setAccepted(undefined)
    },
    onSuccess: (result) => {
      setAccepted(result)
      setSelected(result.syncRunId ?? result.resources[0]?.syncRunId)
      void client.invalidateQueries({
        queryKey: ['sources', sourceId, 'sync-runs'],
      })
    },
    onSettled: () => {
      inFlight.current = false
    },
  })
  const validDates =
    (!from || Number.isFinite(Date.parse(from))) &&
    (!to || Number.isFinite(Date.parse(to))) &&
    (!from || !to || new Date(from) < new Date(to))
  const selectable =
    resources.data?.filter(
      (resource) => resource.isReadable && resource.resourceKind === 'TABLE',
    ) ?? []
  const validSelection =
    all ||
    (ids.length > 0 &&
      ids.every((id) => selectable.some((resource) => resource.id === id)))
  const requestError = request.error instanceof ApiError ? request.error : null
  const affectedIds = Array.isArray(requestError?.details.ids)
    ? requestError.details.ids.filter(
        (id): id is number => Number.isSafeInteger(id) && id > 0,
      )
    : []

  function resourceLabel(id: number) {
    return (
      resources.data?.find((resource) => resource.id === id)?.resourceKey ??
      `테이블 #${id}`
    )
  }

  function submit() {
    if (!api || inFlight.current || !validSelection) return
    inFlight.current = true
    request.mutate()
  }
  function selectRun(run: SyncRun) {
    setSelected(run.syncRunId)
  }

  return (
    <div className="source-sync-runs text-sm">
      <section aria-label="수동 수집" className="source-sync-manual">
        <h3 className="font-semibold">수동 수집</h3>
        <p>
          선택한 테이블을 전체 다시 읽습니다. 이미 대기·실행 중인 테이블은 기존
          실행에 합류합니다.
        </p>
        {!api && (
          <p role="status" className="text-slate-500">
            수동 수집과 실행 이력 조회는 아직 사용할 수 없습니다.
          </p>
        )}
        <fieldset disabled={request.isPending} className="source-sync-request">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={all}
              onChange={(e) => setAll(e.target.checked)}
            />
            활성 매핑이 있는 모든 테이블 수집
          </label>
          {!all && (
            <>
              {resources.isPending ? (
                <LoadingState message="수집 대상을 불러오는 중…" />
              ) : resources.isError ? (
                <ErrorState
                  error={resources.error}
                  onRetry={() => {
                    void resources.refetch()
                  }}
                />
              ) : selectable.length ? (
                <div className="max-h-64 space-y-1 overflow-auto">
                  {selectable.map((resource) => (
                    <label key={resource.id} className="flex gap-2">
                      <input
                        type="checkbox"
                        checked={ids.includes(resource.id)}
                        onChange={(e) =>
                          setIds((current) =>
                            e.target.checked
                              ? [...current, resource.id]
                              : current.filter((id) => id !== resource.id),
                          )
                        }
                      />
                      {resource.resourceKey}
                    </label>
                  ))}
                </div>
              ) : (
                <EmptyState message="선택 가능한 테이블이 없습니다." />
              )}
              {!validSelection && (
                <p>수집할 테이블을 1개 이상 선택해 주세요.</p>
              )}
            </>
          )}
          <Button
            disabled={!api || request.isPending || !validSelection}
            onClick={submit}
          >
            {request.isPending ? '접수 중…' : '수동 수집 요청'}
          </Button>
        </fieldset>
        {request.isError && (
          <>
            <ErrorState error={request.error} />
            {affectedIds.length > 0 && (
              <p>
                확인이 필요한 테이블:{' '}
                {affectedIds.map(resourceLabel).join(', ')}
              </p>
            )}
            {requestError?.status === 409 &&
              requestError.details.recoveryReason === 'SOURCE_CHANGED' && (
                <p>
                  원천 접속 정보가 변경되었습니다. CDC 재연결 후 다시 시도해
                  주세요.
                </p>
              )}
            {(!requestError ||
              ['network', 'server', 'invalid-response'].includes(
                requestError.kind,
              )) && (
              <p>
                접수 여부가 불확실하면 자동 갱신되는 실행 이력에서 확인해
                주세요.
              </p>
            )}
          </>
        )}
        {accepted && (
          <div role="status" className="space-y-2 rounded bg-slate-50 p-3">
            <p>
              {accepted.syncRunId == null
                ? '새 실행 없이 기존 수집에 합류했습니다.'
                : `수집 실행 #${accepted.syncRunId}을 접수했습니다.`}
            </p>
            {accepted.joinedExisting && (
              <p>일부 또는 모든 테이블은 기존 실행 결과를 함께 확인합니다.</p>
            )}
            {accepted.resources.map((resource) => (
              <p key={resource.sourceResourceId}>
                {resourceLabel(resource.sourceResourceId)} ·{' '}
                {resource.joined ? '기존 실행 합류' : '새 실행'} ·{' '}
                <button
                  type="button"
                  onClick={() => setSelected(resource.syncRunId)}
                  className="underline"
                >
                  실행 #{resource.syncRunId} 보기
                </button>
              </p>
            ))}
          </div>
        )}
      </section>
      <section aria-label="수집 실행 이력" className="source-sync-history">
        <h3 className="font-semibold">수집 실행 이력</h3>
        <form
          className="source-sync-filters"
          onSubmit={(e) => {
            e.preventDefault()
            if (validDates)
              setFilters((current) => ({
                ...current,
                page: 0,
                from: from ? new Date(from).toISOString() : undefined,
                to: to ? new Date(to).toISOString() : undefined,
              }))
          }}
        >
          <label>
            실행 상태{' '}
            <select
              value={filters.status ?? ''}
              onChange={(e) =>
                setFilters((current) => ({
                  ...current,
                  page: 0,
                  status: (e.target.value as CheckStatus) || undefined,
                }))
              }
            >
              <option value="">전체 상태</option>
              {Object.entries(statuses)
                .filter(([key]) => key !== 'SKIPPED')
                .map(([key, label]) => (
                  <option key={key} value={key}>
                    {label}
                  </option>
                ))}
            </select>
          </label>
          <label>
            시작 원인{' '}
            <select
              value={filters.triggerKind ?? ''}
              onChange={(e) =>
                setFilters((current) => ({
                  ...current,
                  page: 0,
                  triggerKind: (e.target.value as SyncTrigger) || undefined,
                }))
              }
            >
              <option value="">CDC 제외</option>
              {Object.entries(triggers).map(([key, label]) => (
                <option key={key} value={key}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label>
            접수 시작(포함)
            <input
              type="datetime-local"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label>
            접수 종료(미포함)
            <input
              type="datetime-local"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          <Button type="submit" variant="secondary" disabled={!validDates}>
            기간 적용
          </Button>
        </form>
        {!validDates && (
          <p role="alert">종료 시각은 시작 시각보다 늦어야 합니다.</p>
        )}
        {api &&
          (history.isPending ? (
            <LoadingState message="수집 이력을 불러오는 중…" />
          ) : !history.data || historyUnavailable ? (
            <ErrorState
              error={history.error!}
              onRetry={() => {
                void history.refetch()
              }}
            />
          ) : (
            <>
              {history.data.data.length ? (
                <ul className="source-sync-list" aria-busy={history.isFetching}>
                  {history.data.data.map((run) => (
                    <li key={run.id} className="source-sync-item">
                      <button
                        type="button"
                        onClick={() => selectRun(run)}
                        className="font-semibold underline"
                      >
                        수집 실행 #{run.syncRunId}
                      </button>{' '}
                      · {triggers[run.triggerKind]} · {statuses[run.status]}
                      <p>
                        접수 {time(run.requestedAt)} · 완료{' '}
                        {time(run.finishedAt)}
                      </p>
                      <p>
                        읽음{' '}
                        {run.resources.reduce((sum, r) => sum + r.rowsRead, 0)}{' '}
                        · 생성·갱신{' '}
                        {run.resources.reduce(
                          (sum, r) => sum + r.rowsUpserted,
                          0,
                        )}{' '}
                        · 삭제{' '}
                        {run.resources.reduce(
                          (sum, r) => sum + r.rowsDeleted,
                          0,
                        )}
                      </p>
                      {run.errorMessage && (
                        <p className="whitespace-pre-wrap break-words text-red-700">
                          {run.errorMessage}
                        </p>
                      )}
                    </li>
                  ))}
                </ul>
              ) : (
                <EmptyState message="수집 실행 이력이 없습니다." />
              )}
              {history.data.meta && (
                <Pagination
                  page={filters.page}
                  size={SYNC_RUN_PAGE_SIZE}
                  totalElements={history.data.meta.totalElements}
                  onChange={(page) =>
                    setFilters((current) => ({ ...current, page }))
                  }
                />
              )}
              {history.isError && !historyTransient && (
                <ErrorState
                  error={history.error}
                  onRetry={() => void history.refetch()}
                />
              )}
              {historyDelayed && (
                <p role="status" className="text-slate-500">
                  이력 갱신이 지연되고 있습니다. 기존 목록을 유지하며 자동으로
                  다시 확인합니다.
                </p>
              )}
            </>
          ))}
      </section>
      {api && selected !== undefined && (
        <RunDetail
          key={selected}
          id={selected}
          api={api}
          resourceLabel={resourceLabel}
        />
      )}
    </div>
  )
}
