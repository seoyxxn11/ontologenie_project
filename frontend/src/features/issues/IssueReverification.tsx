import { formatKstDateTime } from '../../utils/dateTime'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchRunResults } from '../review/checkApi'
import { Result } from '../review/CheckResults'
import { fetchResolutionRuleId, type Issue } from './api'
import ReverificationHistory from './ReverificationHistory'
import {
  fetchReverificationRun,
  requestReverification,
  type ReverificationAccepted,
} from './reverificationApi'

const active = (status?: string) => status === 'QUEUED' || status === 'RUNNING'
const runStatuses = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
}
const time = (value: string | null) => (value ? formatKstDateTime(value) : '—')

function RunResults({
  runId,
  filter,
  title,
}: {
  runId: number
  filter: Exclude<Parameters<typeof fetchRunResults>[1], undefined>
  title: string
}) {
  const [page, setPage] = useState(0)
  const query = useQuery({
    queryKey: ['issues', 'check-results', runId, filter, page],
    queryFn: ({ signal }) => fetchRunResults(runId, filter, page, signal),
    retry: false,
  })
  return (
    <section aria-label={title} className="space-y-2">
      <h4 className="font-medium">
        {title} · 실행 #{runId}
      </h4>
      {query.isPending && <LoadingState message="검사 결과를 불러오는 중…" />}
      {query.isError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}
      {query.isSuccess && (
        <>
          {query.data.data.length ? (
            query.data.data.map((result) => (
              <Result key={result.id} result={result} />
            ))
          ) : (
            <EmptyState message="이 대상의 검사 결과가 없습니다." />
          )}
          {(query.data.meta.totalElements > query.data.meta.size ||
            page > 0) && <Pagination {...query.data.meta} onChange={setPage} />}
        </>
      )}
    </section>
  )
}

function ResolvedRunResults({
  issueId,
  runId,
}: {
  issueId: number
  runId: number
}) {
  const query = useQuery({
    queryKey: ['issues', 'resolution-rule', issueId, runId],
    queryFn: ({ signal }) => fetchResolutionRuleId(issueId, runId, signal),
    retry: false,
  })
  const title = '해결 처리에 사용한 재검증 결과'
  if (query.isSuccess && query.data != null) {
    return (
      <RunResults
        runId={runId}
        filter={{ linkMappingId: query.data }}
        title={title}
      />
    )
  }
  return (
    <section aria-label={title} className="space-y-2">
      <h4 className="font-medium">
        {title} · 실행 #{runId}
      </h4>
      {query.isPending && (
        <LoadingState message="해결에 사용한 규칙 정보를 불러오는 중…" />
      )}
      {query.isError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}
      {query.isSuccess && (
        <EmptyState message="해결에 사용한 규칙 정보를 확인할 수 없습니다." />
      )}
    </section>
  )
}

export default function IssueReverification({ issue }: { issue: Issue }) {
  const [open, setOpen] = useState(false)
  const [params, setParams] = useSearchParams()
  const client = useQueryClient()
  const mounted = useRef(false)
  const submitting = useRef(false)
  const refreshed = useRef('')
  const [receipt, setReceipt] = useState<ReverificationAccepted>()
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const {
    linkMappingId,
    proposalItemId,
    detectedCheckRunId,
    resolvedCheckRunId,
  } = issue
  const filter =
    linkMappingId != null
      ? { linkMappingId }
      : proposalItemId != null
        ? { proposalItemId }
        : null
  const selected = Number(params.get('reverifyRun'))
  const runId =
    params.get('reverifyIssue') === String(issue.id) &&
    Number.isSafeInteger(selected) &&
    selected > 0
      ? selected
      : undefined
  const queryKey = [
    'issues',
    'reverification',
    issue.id,
    detectedCheckRunId,
    runId,
  ]
  const execution = useQuery({
    queryKey,
    queryFn: ({ signal }) =>
      fetchReverificationRun(runId!, issue.id, detectedCheckRunId, signal),
    enabled: runId !== undefined,
    retry: false,
    refetchInterval: (query) =>
      !query.state.error && active(query.state.data?.status) ? 2000 : false,
  })
  const run = execution.data
  useEffect(() => {
    if (!run || active(run.status)) return
    const key = `${run.id}:${run.status}`
    if (refreshed.current === key) return
    refreshed.current = key
    void Promise.all([
      client.invalidateQueries({ queryKey: ['issues', 'detail', issue.id] }),
      client.invalidateQueries({ queryKey: ['issues', 'list'] }),
      client.invalidateQueries({ queryKey: ['issues', 'history', issue.id] }),
      client.invalidateQueries({
        queryKey: ['issues', 'verifications', issue.id],
      }),
      client.invalidateQueries({
        queryKey: ['issues', 'reverification-comparison', run.id],
      }),
      client.invalidateQueries({
        queryKey: ['issues', 'check-results', run.id],
      }),
    ])
  }, [client, issue.id, run])
  const request = useMutation({
    mutationFn: () => requestReverification(issue.id, detectedCheckRunId),
    retry: false,
    onSuccess: (result) => {
      client.setQueryData(
        ['issues', 'reverification', issue.id, detectedCheckRunId, result.id],
        result,
      )
      if (!mounted.current) return
      setReceipt(result)
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          next.set('reverifyIssue', String(issue.id))
          next.set('reverifyRun', String(result.id))
          return next
        },
        { replace: true },
      )
    },
    onError: (error) => {
      if (error instanceof ApiError && [409, 422].includes(error.status ?? 0)) {
        void client.invalidateQueries({
          queryKey: ['issues', 'detail', issue.id],
        })
        void client.invalidateQueries({
          queryKey: ['issues', 'history', issue.id],
        })
      }
    },
    onSettled: () => {
      submitting.current = false
    },
  })
  const validTarget =
    linkMappingId != null &&
    linkMappingId > 0 &&
    Number.isSafeInteger(detectedCheckRunId) &&
    detectedCheckRunId > 0
  const locked =
    request.isPending ||
    (runId !== undefined &&
      (execution.isPending || execution.isError || active(run?.status)))
  function start() {
    if (
      submitting.current ||
      locked ||
      !validTarget ||
      issue.status !== 'FIXED_PENDING_REVERIFY'
    )
      return
    submitting.current = true
    request.mutate()
  }
  const uncertain =
    request.error &&
    (!(request.error instanceof ApiError) ||
      ['network', 'server', 'invalid-response'].includes(request.error.kind))
  return (
    <section aria-label="재검증" className="space-y-3">
      <h3 className="font-semibold">재검증</h3>
      {issue.status === 'FIXED_PENDING_REVERIFY' && (
        <>
          <Button
            variant="secondary"
            disabled={!validTarget || locked}
            onClick={start}
          >
            {request.isPending ? '재검증 요청 중…' : '재검증 실행'}
          </Button>
          {!validTarget && (
            <p className="text-sm text-slate-500">
              반영된 연결 규칙에서 발견한 문제와 검사 기록이 필요합니다.
            </p>
          )}
        </>
      )}
      {request.error && <ErrorState error={request.error} />}
      {uncertain && (
        <p>
          접수 여부가 불확실합니다. 다시 요청하기 전에{' '}
          <Link to="/audit" className="underline">
            실행 이력
          </Link>
          을 확인해 주세요.
        </p>
      )}
      {receipt && receipt.id === runId && (
        <p role="status">
          {receipt.reused
            ? '같은 수집 기준의 완료 결과를 재사용합니다.'
            : receipt.joined
              ? '이미 대기·실행 중인 재검증에 합류했습니다.'
              : `재검증 실행 #${receipt.id}을 접수했습니다.`}
        </p>
      )}
      {runId !== undefined &&
        (execution.isPending ? (
          <LoadingState message="재검증 상태를 불러오는 중…" />
        ) : execution.isError ? (
          <ErrorState
            error={execution.error}
            onRetry={() => void execution.refetch()}
          />
        ) : (
          run && (
            <section
              aria-label="재검증 실행 상태"
              className="space-y-2 rounded-lg bg-slate-50 p-3 text-sm"
            >
              <h4 className="font-semibold">
                재검증 실행 #{run.id} · {runStatuses[run.status]}
              </h4>
              <p>
                비교 기준: 최초 발견 검사 #{run.comparedToRunId} · 현재 검사
                규칙 #{run.scope.linkMappingIds[0]}
              </p>
              <p>
                접수 {time(run.requestedAt)} · 시작 {time(run.startedAt)} · 종료{' '}
                {time(run.finishedAt)}
              </p>
              {run.status === 'FAILED' && (
                <p role="alert">
                  {run.errorMessage ?? '재검증 실행에 실패했습니다.'}
                </p>
              )}
              <details>
                <summary>검사 데이터 기준</summary>
                <p>
                  {run.status === 'SUCCEEDED'
                    ? '실제 검사에 사용한 수집 기준입니다.'
                    : '접수 시점 기준이며, 실제 사용한 기준은 완료 후 확인할 수 있습니다.'}
                </p>
                <ul>
                  {run.dataBasis.resources.map((resource) => (
                    <li key={resource.sourceResourceId}>
                      원천 자원 #{resource.sourceResourceId} ·{' '}
                      {resource.lastSyncRunId == null
                        ? '성공 수집 기록 없음'
                        : `성공 수집 #${resource.lastSyncRunId}`}
                    </li>
                  ))}
                </ul>
              </details>
              {run.status === 'SUCCEEDED' && (
                <p>
                  실행 완료는 개별 문제의 통과·해결 판정이 아닙니다. 문제별 판정
                  근거 확인 후 별도로 해결 처리해야 합니다.
                </p>
              )}
            </section>
          )
        ))}
      <details onToggle={(event) => setOpen(event.currentTarget.open)}>
        <summary className="cursor-pointer text-sm font-medium">
          검사 결과 비교
        </summary>
        {open && (
          <div className="mt-3 space-y-4">
            {filter && detectedCheckRunId > 0 ? (
              <RunResults
                key={`detected:${detectedCheckRunId}`}
                title="발견 당시 검사 결과"
                runId={detectedCheckRunId}
                filter={filter}
              />
            ) : (
              <p className="text-sm text-slate-500">
                이 문제에서 조회할 수 있는 검사 기록이 없습니다.
              </p>
            )}
            {resolvedCheckRunId != null &&
            resolvedCheckRunId !== detectedCheckRunId ? (
              <ResolvedRunResults
                key={`resolved:${resolvedCheckRunId}`}
                issueId={issue.id}
                runId={resolvedCheckRunId}
              />
            ) : (
              <p className="text-sm text-slate-500">
                이 문제에 연결된 해결 재검증 기록이 없습니다.
              </p>
            )}
            {execution.isSuccess &&
              run?.status === 'SUCCEEDED' &&
              run.id !== resolvedCheckRunId && (
                <RunResults
                  key={`current:${run.id}`}
                  title="이번 재검증 검사 결과"
                  runId={run.id}
                  filter={{ linkMappingId: run.scope.linkMappingIds[0]! }}
                />
              )}
            <ReverificationHistory
              key={`history:${runId ?? 'none'}`}
              issueId={issue.id}
              completedRunId={run?.status === 'SUCCEEDED' ? run.id : undefined}
              resolvedRunId={resolvedCheckRunId}
            />
          </div>
        )}
      </details>
    </section>
  )
}
