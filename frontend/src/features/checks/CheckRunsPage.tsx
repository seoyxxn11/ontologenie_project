import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import './checks.css'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import {
  activeRun,
  fetchCheckRuns,
  runStatuses,
  runTriggers,
  type CheckRunAccepted,
  type CheckRunFilters,
} from './api'
import CheckRunDetail from './CheckRunDetail'
import { runTime } from './format'
import ManualCheckForm from './ManualCheckForm'

export default function CheckRunsPage() {
  const [params, setParams] = useSearchParams()
  const [triggerKind, setTriggerKind] = useState<
    CheckRunFilters['triggerKind'] | ''
  >('')
  const [status, setStatus] = useState<CheckRunFilters['status'] | ''>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [formError, setFormError] = useState('')
  const [filters, setFilters] = useState<CheckRunFilters>({})
  const [page, setPage] = useState(0)
  const [manualOpen, setManualOpen] = useState(false)
  const [receipt, setReceipt] = useState<CheckRunAccepted>()
  const selected = Number(params.get('run'))
  const runId =
    Number.isSafeInteger(selected) && selected > 0 ? selected : undefined
  const query = useQuery({
    queryKey: ['checks', 'runs', filters, page],
    queryFn: ({ signal }) => fetchCheckRuns(filters, page, signal),
    retry: false,
    refetchInterval: (query) =>
      !query.state.error &&
      query.state.data?.data.some((run) => activeRun(run.status))
        ? 2000
        : false,
  })
  function selectRun(id: number) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      next.set('run', String(id))
      return next
    })
  }
  function applyFilters(next: CheckRunFilters) {
    if (JSON.stringify(next) !== JSON.stringify(filters)) {
      setParams((current) => {
        const nextParams = new URLSearchParams(current)
        nextParams.delete('run')
        return nextParams
      })
    }
    setFilters(next)
    setPage(0)
  }
  return (
    <div className="management-page checks-page">
      <h1 className="ui-page-title">정합성 검사</h1>
      <div className="checks-toolbar">
        <Link to="/admin/rules">← 연결 규칙으로 돌아가기</Link>
        <div className="checks-actions">
          <Button
            variant="secondary"
            pending={query.isFetching}
            onClick={() => void query.refetch()}
          >
            실행 목록 새로고침
          </Button>
          <Button
            aria-label="수동 검사 열기"
            onClick={() => setManualOpen(true)}
          >
            수동 검사 실행
          </Button>
        </div>
      </div>
      {manualOpen && (
        <Dialog title="수동 검사 실행" onClose={() => setManualOpen(false)}>
          <ManualCheckForm
            onAccepted={(run) => {
              setReceipt(run)
              selectRun(run.id)
              setManualOpen(false)
            }}
          />
        </Dialog>
      )}
      {receipt && receipt.id === runId && (
        <p role="status" className="text-sm">
          {receipt.reused
            ? `같은 기준의 완료 결과 #${receipt.id}을 재사용합니다.`
            : receipt.joined
              ? `이미 대기·실행 중인 검사 #${receipt.id}에 합류했습니다.`
              : `검사 실행 #${receipt.id}을 접수했습니다.`}
        </p>
      )}
      <form
        className="checks-filter"
        onSubmit={(event) => {
          event.preventDefault()
          if (
            (from && !Number.isFinite(Date.parse(from))) ||
            (to && !Number.isFinite(Date.parse(to))) ||
            (from && to && new Date(from) > new Date(to))
          ) {
            setFormError('종료 시각은 시작 시각 이후로 선택해 주세요.')
            return
          }
          setFormError('')
          applyFilters({
            triggerKind: triggerKind || undefined,
            status: status || undefined,
            from: from ? new Date(from).toISOString() : undefined,
            to: to ? new Date(to).toISOString() : undefined,
          })
        }}
      >
        <label className="flex flex-col gap-1">
          검사 종류
          <select
            value={triggerKind}
            onChange={(event) =>
              setTriggerKind(event.target.value as typeof triggerKind)
            }
          >
            <option value="">전체</option>
            {Object.entries(runTriggers).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          실행 상태
          <select
            value={status}
            onChange={(event) => setStatus(event.target.value as typeof status)}
          >
            <option value="">전체</option>
            {Object.entries(runStatuses).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          접수 시작 시각
          <input
            type="datetime-local"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1">
          접수 종료 시각
          <input
            type="datetime-local"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </label>
        <Button type="submit">검색</Button>
        <Button
          variant="secondary"
          onClick={() => {
            setTriggerKind('')
            setStatus('')
            setFrom('')
            setTo('')
            applyFilters({})
            setFormError('')
          }}
        >
          초기화
        </Button>
      </form>
      {formError && (
        <p role="alert" className="text-sm text-red-700">
          {formError}
        </p>
      )}
      <div className="checks-workspace">
        <section aria-label="검사 실행 목록" className="checks-runs">
          <div className="checks-list-heading">
            <h2>검사 실행 목록</h2>
            {query.data && <span>{query.data.meta.totalElements}건</span>}
          </div>
          {query.isPending ? (
            <LoadingState message="검사 실행 목록을 불러오는 중…" />
          ) : query.isError ? (
            <ErrorState
              error={query.error}
              onRetry={() => void query.refetch()}
            />
          ) : (
            <>
              {!query.data.data.length ? (
                <EmptyState message="조건에 맞는 검사 실행이 없습니다." />
              ) : (
                <ul className="checks-run-items">
                  {query.data.data.map((run) => (
                    <li key={run.id}>
                      <button
                        type="button"
                        className="checks-run"
                        aria-label={'검사 #' + run.id + ' 상세 보기'}
                        aria-pressed={runId === run.id}
                        onClick={() => selectRun(run.id)}
                      >
                        <span className="checks-run-heading">
                          <strong>
                            #{run.id} · {runTriggers[run.triggerKind]}
                          </strong>
                          <StatusBadge
                            tone={
                              run.status === 'FAILED'
                                ? 'danger'
                                : run.status === 'SUCCEEDED'
                                  ? 'success'
                                  : 'info'
                            }
                          >
                            {run.status === 'SUCCEEDED'
                              ? '실행 완료'
                              : runStatuses[run.status]}
                          </StatusBadge>
                        </span>
                        <span className="checks-secondary">
                          {runTime(run.requestedAt)}
                        </span>
                        <span className="checks-secondary">
                          {run.triggerKind === 'CANDIDATE'
                            ? '승인 전 후보'
                            : (run.scope.linkMappingIds?.length ?? 0) +
                              '개 규칙'}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <Pagination
                {...query.data.meta}
                onChange={setPage}
                pending={query.isFetching}
                compact
                showSummary={false}
              />
            </>
          )}
        </section>
        <div className="checks-detail">
          {runId !== undefined ? (
            <CheckRunDetail key={runId} id={runId} />
          ) : (
            <div className="management-panel">
              <EmptyState message="검사 실행을 선택하면 집계와 판정 근거를 확인할 수 있습니다." />
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
