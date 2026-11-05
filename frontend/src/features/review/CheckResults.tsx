import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import Pagination from '../../components/Pagination'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import {
  CHECK_PAGE_SIZE,
  fetchCheckResults,
  type CheckResult,
} from './checkApi'
import type { Proposal } from './types'

const states: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
}
const outcomes: Record<string, string> = {
  MATCHED: '정상',
  UNMATCHED: '미연결',
  DUPLICATE: '중복',
  VIOLATION: '조건 위반',
}
function valueText(value: unknown) {
  if (value == null) return '제공되지 않음'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function Result({ result }: { result: CheckResult }) {
  return (
    <article className="space-y-4 rounded-xl border border-violet-200 bg-violet-50/20 p-4">
      <h4 className="font-semibold">
        결과 #{result.id}
        {result.linkMappingId == null
          ? ''
          : ` · 연결 규칙 #${result.linkMappingId}`}
      </h4>
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {[
          ['전체', result.totalCount],
          ['정상', result.matchedCount],
          ['미연결', result.unmatchedCount],
          ['중복', result.duplicateCount],
          ['조건 위반', result.violationCount],
        ].map(([name, count]) => (
          <div key={name}>
            <dt className="text-sm text-slate-600">{name}</dt>
            <dd className="text-xl font-semibold">{count}건</dd>
          </div>
        ))}
      </dl>
      <p>
        시험 매칭률:{' '}
        {result.matchRate == null || !Number.isFinite(result.matchRate)
          ? '계산할 수 없음'
          : `${(result.matchRate * 100).toFixed(1)}%`}
      </p>
      <p className="text-sm text-slate-600">
        시험 매칭률은 검사 대상의 정상 비율이며 AI 신뢰도와 다릅니다. 아래는
        서버가 제공한 표본이며 전체 행 목록이 아닙니다.
      </p>
      {!result.sampleRows.length ? (
        <EmptyState message="제공된 판정 근거 표본이 없습니다." />
      ) : (
        <DataTable label={`검사 결과 ${result.id} 판정 근거`}>
          <thead>
            <tr>
              {[
                '행',
                '원본 값',
                '변환 값',
                '대응 대상',
                '결과',
                '실패 이유',
              ].map((label) => (
                <th key={label} className="p-3">
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {result.sampleRows.map((row, index) => (
              <tr key={index} className="border-t border-slate-200">
                {[
                  row.sourceKey ?? row.rowId ?? index + 1,
                  row.rawValue,
                  row.transformedValue,
                  row.targetKey ?? row.targetValue,
                  typeof row.classification === 'string'
                    ? (outcomes[row.classification] ?? row.classification)
                    : undefined,
                  row.reason,
                ].map((value, column) => (
                  <td
                    key={column}
                    className="max-w-sm whitespace-pre-wrap break-words p-3"
                  >
                    {valueText(value)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </DataTable>
      )}
    </article>
  )
}

function CompletedResults({
  proposal,
  runId,
}: {
  proposal: Proposal
  runId: number
}) {
  const [page, setPage] = useState(0)
  const query = useQuery({
    queryKey: [
      'proposals',
      'checks',
      proposal.id,
      proposal.revision,
      runId,
      page,
    ],
    queryFn: ({ signal }) =>
      fetchCheckResults(runId, proposal.id, proposal.revision, page, signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  if (query.isPending)
    return <LoadingState message="검사 결과를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  return (
    <div className="space-y-4">
      {query.data.data.length ? (
        query.data.data.map((result) => (
          <Result key={result.id} result={result} />
        ))
      ) : (
        <EmptyState message="현재 제안 개정에 해당하는 검사 결과가 없습니다." />
      )}
      <Pagination
        page={page}
        size={CHECK_PAGE_SIZE}
        totalElements={query.data.meta.totalElements}
        onChange={setPage}
      />
    </div>
  )
}

export default function CheckResults({
  proposal,
  refreshing,
  refreshError,
  onRefresh,
}: {
  proposal: Proposal
  refreshing: boolean
  refreshError?: Error | null
  onRefresh: () => void
}) {
  const run = proposal.latestCheck
  return (
    <section
      className="space-y-4 rounded-xl border border-slate-200 p-5"
      aria-label="정합성 검사"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-semibold">최근 정합성 검사</h3>
        <Button variant="secondary" pending={refreshing} onClick={onRefresh}>
          검사 상태 새로고침
        </Button>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="secondary" disabled>
          함께 재검사
        </Button>
        <p className="text-sm text-slate-500">
          수동 검사 실행 기능은 준비 중입니다.
        </p>
      </div>
      {refreshError ? (
        <ErrorState error={refreshError} onRetry={onRefresh} />
      ) : !run ? (
        <EmptyState message="아직 검사 결과가 없습니다." />
      ) : (
        <>
          <p role="status">
            검사 #{run.checkRunId} · {states[run.status] ?? run.status} · 제안
            개정 {proposal.revision}
          </p>
          {['QUEUED', 'RUNNING'].includes(run.status) && (
            <LoadingState message="검사가 진행 중입니다. 3초마다 상태를 확인합니다." />
          )}
          {run.status === 'FAILED' && (
            <p role="alert" className="rounded-lg bg-red-50 p-3 text-red-800">
              검사 실행에 실패했습니다. 부분 결과를 정상 결과로 표시하지
              않습니다.
            </p>
          )}
          {run.status === 'SUCCEEDED' && (
            <CompletedResults
              key={`${proposal.id}:${proposal.revision}:${run.checkRunId}`}
              proposal={proposal}
              runId={run.checkRunId}
            />
          )}
        </>
      )}
    </section>
  )
}
