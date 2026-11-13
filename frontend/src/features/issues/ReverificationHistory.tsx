import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchIssueVerifications, fetchReverificationComparison } from './api'

const categories = [
  ['RESOLVED', '해결 문제'],
  ['REMAINING', '잔존 문제'],
  ['NEW', '신규 문제'],
] as const

function ComparisonResults({ runId }: { runId: number }) {
  const [page, setPage] = useState(0)
  const query = useQuery({
    queryKey: ['issues', 'reverification-comparison', runId, page],
    queryFn: ({ signal }) => fetchReverificationComparison(runId, page, signal),
    retry: false,
  })
  if (query.isPending)
    return <LoadingState message="재검증 비교 결과를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    )
  const { comparedToRunId, coverage, hasBaselinePassEvidence, rows } =
    query.data.data
  const { meta } = query.data
  return (
    <section aria-label="재검증 비교 결과" className="space-y-3">
      <h4 className="font-medium">
        실행 #{comparedToRunId} → #{runId}
      </h4>
      {coverage === 'PARTIAL' && (
        <p className="text-sm text-slate-600">
          저장된 판정 근거로 확인할 수 있는 행만 표시합니다. 신규 문제는 이전에
          정상으로 확인된 항목만 표시합니다. 이전 판정 기록이 없는 항목은 새로
          생긴 문제인지 알 수 없어요.
        </p>
      )}
      {coverage === 'UNAVAILABLE' && (
        <EmptyState message="두 실행에서 비교할 수 있는 판정 기록이 없어 새로 생긴 문제인지 알 수 없어요." />
      )}
      {coverage !== 'UNAVAILABLE' &&
        categories
          .filter(([category]) => category !== 'NEW' || hasBaselinePassEvidence)
          .map(([category, label]) => (
            <section key={category} aria-label={label}>
              <h5 className="font-medium">{label}</h5>
              <ul className="space-y-1 text-sm">
                {rows
                  .filter((row) => row.category === category)
                  .map((row) => (
                    <li key={`${row.ruleCode}:${row.objectInstanceId}`}>
                      {row.ruleCode} · 객체 #{row.objectInstanceId}
                      <div>이전 · {evidenceText(row.before)}</div>
                      <div>이번 · {evidenceText(row.after)}</div>
                    </li>
                  ))}
              </ul>
            </section>
          ))}
      {coverage === 'PARTIAL' && !hasBaselinePassEvidence && (
        <section aria-label="신규 문제">
          <h5 className="font-medium">신규 문제 확인 불가</h5>
          <p className="text-sm text-slate-600">
            이전 검사에 정상 판정 기록이 없어 새로 생긴 문제인지 알 수 없어요.
          </p>
        </section>
      )}
      {coverage !== 'UNAVAILABLE' && meta?.totalElements === 0 && (
        <EmptyState message="확인된 문제 변화가 없습니다." />
      )}
      {meta && (meta.totalElements > meta.size || page > 0) && (
        <Pagination {...meta} onChange={setPage} />
      )}
    </section>
  )
}

function evidenceText(evidence: {
  classification?: string
  reason?: string | null
  rawValue?: string | null
  transformedValue?: string | null
}) {
  const labels: Record<string, string> = {
    MATCHED: '정상',
    UNMATCHED: '미연결',
    DUPLICATE: '중복',
    VIOLATION: '조건 위반',
  }
  return [
    evidence.classification &&
      (labels[evidence.classification] ?? evidence.classification),
    evidence.reason,
    evidence.rawValue != null && `원본 ${evidence.rawValue}`,
    evidence.transformedValue != null && `변환 ${evidence.transformedValue}`,
  ]
    .filter(Boolean)
    .join(' · ')
}

export default function ReverificationHistory({
  issueId,
  completedRunId,
  resolvedRunId,
}: {
  issueId: number
  completedRunId?: number
  resolvedRunId: number | null
}) {
  const [page, setPage] = useState(0)
  const [selectedRunId, setSelectedRunId] = useState<number>()
  const history = useQuery({
    queryKey: ['issues', 'verifications', issueId, page],
    queryFn: ({ signal }) => fetchIssueVerifications(issueId, page, signal),
    retry: false,
  })
  const comparisonRunId =
    selectedRunId ??
    completedRunId ??
    resolvedRunId ??
    history.data?.data.find(isCompletedReverification)?.checkRunId

  return (
    <>
      <section aria-label="문제 재검증 이력" className="space-y-2">
        <h4 className="font-medium">문제 재검증 이력</h4>
        {history.isPending && <LoadingState message="이력을 불러오는 중…" />}
        {history.isError && (
          <ErrorState
            error={history.error}
            onRetry={() => void history.refetch()}
          />
        )}
        {history.isSuccess && (
          <>
            {history.data.data.length ? (
              <ul className="space-y-2 text-sm">
                {history.data.data.map((record) => (
                  <li key={record.checkRunId}>
                    <span>
                      실행 #{record.checkRunId} ·{' '}
                      {record.outcome === 'PASSED' ? '통과' : '실패'}
                    </span>
                    {' · '}
                    <time dateTime={record.checkedAt}>
                      {formatKstDateTime(record.checkedAt)}
                    </time>
                    <div>{evidenceText(record.detail)}</div>
                    {isCompletedReverification(record) && (
                      <Button
                        variant="secondary"
                        onClick={() => setSelectedRunId(record.checkRunId)}
                      >
                        실행 #{record.checkRunId} 비교 보기
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState message="저장된 판정 이력이 없습니다." />
            )}
            {history.data.meta &&
              (history.data.meta.totalElements > history.data.meta.size ||
                page > 0) && (
                <Pagination {...history.data.meta} onChange={setPage} />
              )}
          </>
        )}
      </section>
      {comparisonRunId != null && (
        <ComparisonResults key={comparisonRunId} runId={comparisonRunId} />
      )}
    </>
  )
}

function isCompletedReverification(record: {
  status: string
  triggerKind: string
}) {
  return record.status === 'SUCCEEDED' && record.triggerKind === 'REVERIFY'
}
