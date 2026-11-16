import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import StatusBadge from '../../components/StatusBadge'
import Dialog from '../../components/Dialog'
import { CHECK_PAGE_SIZE, fetchRunResults } from '../review/checkApi'
import { Result } from '../review/CheckResults'
import { activeRun, fetchCheckRun, runStatuses, runTriggers } from './api'
import { runTime } from './format'

function ResultDialog({
  id,
  candidate,
  initialPage,
  initialIndex,
  onClose,
}: {
  id: number
  candidate: boolean
  initialPage: number
  initialIndex: number
  onClose: () => void
}) {
  const [cursor, setCursor] = useState({
    page: initialPage,
    index: initialIndex,
  })
  const query = useQuery({
    queryKey: ['checks', 'results', id, cursor.page],
    queryFn: ({ signal }) =>
      fetchRunResults(id, undefined, cursor.page, signal),
    retry: false,
    staleTime: Infinity,
  })
  const index = Math.min(cursor.index, (query.data?.data.length ?? 1) - 1)
  const selected = query.data?.data[index]
  const position = cursor.page * CHECK_PAGE_SIZE + index
  return (
    <Dialog title="규칙 판정 근거" size="wide" onClose={onClose}>
      <div className="checks-evidence-dialog">
        <nav
          className="checks-evidence-navigation"
          aria-label="판정 근거 규칙 이동"
        >
          <Button
            variant="secondary"
            disabled={query.isFetching || !selected || position === 0}
            onClick={() =>
              setCursor(
                index > 0
                  ? { ...cursor, index: index - 1 }
                  : { page: cursor.page - 1, index: CHECK_PAGE_SIZE - 1 },
              )
            }
          >
            이전 규칙
          </Button>
          <span aria-live="polite">
            {query.data
              ? `${Math.min(position + 1, query.data.meta.totalElements)} / ${query.data.meta.totalElements}개 결과`
              : '검사 결과 조회 중'}
          </span>
          <Button
            variant="secondary"
            disabled={
              query.isFetching ||
              !selected ||
              position + 1 >= (query.data?.meta.totalElements ?? 0)
            }
            onClick={() =>
              setCursor(
                index + 1 < (query.data?.data.length ?? 0)
                  ? { ...cursor, index: index + 1 }
                  : { page: cursor.page + 1, index: 0 },
              )
            }
          >
            다음 규칙
          </Button>
        </nav>
        {query.isPending ? (
          <LoadingState message="판정 근거를 불러오는 중…" />
        ) : query.isError ? (
          <ErrorState
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        ) : !selected ? (
          <EmptyState message="이 실행에 기록된 검사 결과가 없습니다." />
        ) : (
          <>
            <dl className="checks-evidence-counts">
              {[
                ['전체', selected.totalCount],
                ['정상', selected.matchedCount],
                ['미연결', selected.unmatchedCount],
                ['중복', selected.duplicateCount],
                ['조건 위반', selected.violationCount],
              ].map(([label, count]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{count.toLocaleString()}행</dd>
                </div>
              ))}
            </dl>
            <Result
              key={selected.id}
              result={selected}
              candidate={candidate}
              samplesOnly
            />
          </>
        )}
      </div>
    </Dialog>
  )
}

function RunResults({ id, candidate }: { id: number; candidate: boolean }) {
  const [page, setPage] = useState(0)
  const [selectedIndex, setSelectedIndex] = useState<number>()
  const query = useQuery({
    queryKey: ['checks', 'results', id, page],
    queryFn: ({ signal }) => fetchRunResults(id, undefined, page, signal),
    retry: false,
    staleTime: Infinity,
  })
  if (query.isPending)
    return <LoadingState message="검사 결과를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    )
  return (
    <section aria-label="검사 결과" className="space-y-4">
      <div className="checks-result-heading">
        <h3>규칙별 판정 집계</h3>
        <span className="checks-secondary">각 규칙의 검사 대상 행 수</span>
      </div>
      {query.data.data.length ? (
        <DataTable label="규칙별 판정 집계" className="checks-result-table">
          <colgroup>
            <col className="checks-rule-column" />
            {Array.from({ length: 5 }, (_, index) => (
              <col key={index} />
            ))}
          </colgroup>
          <thead>
            <tr>
              {['연결 규칙', '전체', '정상', '미연결', '중복', '조건 위반'].map(
                (label) => (
                  <th key={label} scope="col">
                    {label}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {query.data.data.map((result, index) => (
              <tr key={result.id} data-selected={selectedIndex === index}>
                <td>
                  <Button
                    variant="text"
                    aria-haspopup="dialog"
                    onClick={() => setSelectedIndex(index)}
                  >
                    {result.linkMappingId == null
                      ? `결과 #${result.id} · 승인 전 후보`
                      : `규칙 #${result.linkMappingId}`}
                  </Button>
                </td>
                {[
                  result.totalCount,
                  result.matchedCount,
                  result.unmatchedCount,
                  result.duplicateCount,
                  result.violationCount,
                ].map((count, index) => (
                  <td
                    key={index}
                    className={
                      index > 1 && count > 0 ? 'checks-issue' : undefined
                    }
                  >
                    {count.toLocaleString()}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </DataTable>
      ) : (
        <EmptyState message="이 실행에 기록된 검사 결과가 없습니다." />
      )}
      {query.data.meta.totalElements > query.data.meta.size && (
        <Pagination
          {...query.data.meta}
          onChange={setPage}
          compact
          showSummary={false}
          pending={query.isFetching}
        />
      )}
      {selectedIndex !== undefined && (
        <ResultDialog
          id={id}
          initialPage={page}
          initialIndex={selectedIndex}
          candidate={candidate}
          onClose={() => setSelectedIndex(undefined)}
        />
      )}
    </section>
  )
}

export default function CheckRunDetail({ id }: { id: number }) {
  const query = useQuery({
    queryKey: ['checks', 'run', id],
    queryFn: ({ signal }) => fetchCheckRun(id, signal),
    retry: false,
    refetchInterval: (query) =>
      !query.state.error && activeRun(query.state.data?.status) ? 2000 : false,
  })
  if (query.isPending)
    return <LoadingState message="검사 상태를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    )
  const run = query.data
  return (
    <section
      aria-label={`검사 실행 #${id} 상세`}
      className="management-panel space-y-4"
    >
      <div className="checks-result-heading checks-run-status-heading">
        <h2>검사 실행 #{id}</h2>
        <StatusBadge
          animate={run.status === 'RUNNING'}
          tone={
            run.status === 'SUCCEEDED'
              ? 'success'
              : run.status === 'FAILED'
                ? 'danger'
                : 'info'
          }
        >
          {run.status === 'SUCCEEDED' ? '실행 완료' : runStatuses[run.status]}
        </StatusBadge>
      </div>
      <p className="text-sm">
        {runTriggers[run.triggerKind]} · 요청자{' '}
        {run.requestedBy == null ? '시스템' : `계정 #${run.requestedBy}`}
      </p>
      <p className="text-sm tabular-nums">
        접수 {runTime(run.requestedAt)} · 시작 {runTime(run.startedAt)} · 종료{' '}
        {runTime(run.finishedAt)}
      </p>
      {run.comparedToRunId != null && (
        <p className="text-sm">비교 기준 검사 #{run.comparedToRunId}</p>
      )}
      <details className="text-sm">
        <summary className="cursor-pointer">검사 범위·데이터 기준</summary>
        <p className="mt-2">
          {run.scope.kind === 'ALL'
            ? '접수 당시 반영 중인 모든 규칙'
            : run.scope.kind === 'ISSUES'
              ? '선택한 문제의 현재 규칙'
              : run.triggerKind === 'CANDIDATE'
                ? '승인 전 후보'
                : '선택한 연결 규칙'}
        </p>
        {!!run.scope.linkMappingIds?.length && (
          <p>
            검사 규칙:{' '}
            {run.scope.linkMappingIds.map((id) => `#${id}`).join(', ')}
          </p>
        )}
        {run.dataBasis.resources.length ? (
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
        ) : (
          <p>기록된 수집 기준이 없습니다.</p>
        )}
      </details>
      {activeRun(run.status) && (
        <LoadingState message="검사가 진행 중입니다. 2초마다 상태를 확인합니다." />
      )}
      {run.status === 'FAILED' && (
        <p role="alert" className="whitespace-pre-wrap text-red-700">
          {run.errorMessage ?? '검사 실행에 실패했습니다.'} 부분 결과를 정상
          결과로 표시하지 않습니다.
        </p>
      )}
      {run.status === 'SUCCEEDED' && (
        <>
          <p className="text-sm text-slate-600">
            실행 완료와 개별 행의 정상 판정은 다릅니다. 아래 집계와 판정 근거를
            확인하세요.
          </p>
          <RunResults id={id} candidate={run.triggerKind === 'CANDIDATE'} />
        </>
      )}
    </section>
  )
}
