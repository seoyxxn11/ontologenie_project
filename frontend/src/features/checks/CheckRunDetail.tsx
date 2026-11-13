import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchRunResults } from '../review/checkApi'
import { Result } from '../review/CheckResults'
import { activeRun, fetchCheckRun, runStatuses, runTriggers } from './api'
import { runTime } from './format'

function RunResults({ id, candidate }: { id: number; candidate: boolean }) {
  const [page, setPage] = useState(0)
  const query = useQuery({
    queryKey: ['checks', 'results', id, page],
    queryFn: ({ signal }) => fetchRunResults(id, undefined, page, signal),
    retry: false,
  })
  if (query.isPending)
    return <LoadingState message="검사 결과를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    )
  return (
    <section aria-label="검사 결과" className="space-y-4">
      <h3 className="font-semibold">규칙별 검사 결과</h3>
      {query.data.data.length ? (
        query.data.data.map((result) => (
          <Result key={result.id} result={result} candidate={candidate} />
        ))
      ) : (
        <EmptyState message="이 실행에 기록된 검사 결과가 없습니다." />
      )}
      <Pagination {...query.data.meta} onChange={setPage} />
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
      <h2 className="font-semibold">
        검사 실행 #{id} · {runStatuses[run.status]}
      </h2>
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
