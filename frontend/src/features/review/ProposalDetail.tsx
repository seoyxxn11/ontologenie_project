import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useLocation } from 'react-router'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import { fetchProposal } from './api'
import {
  applyLabels,
  confidence,
  kindLabels,
  originLabels,
  proposalTitle,
  reviewLabels,
} from './labels'

function RecordView({ value }: { value: Record<string, unknown> }) {
  if (!Object.keys(value).length)
    return <EmptyState message="정보가 제공되지 않았습니다." />
  return (
    <dl className="divide-y divide-slate-100">
      {Object.entries(value).map(([key, entry]) => (
        <div key={key} className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="break-words font-medium text-slate-600">
            {(
              {
                reason: '제안 이유',
                name: '이름',
                code: '코드',
                description: '설명',
                originalText: '원문',
                sourceText: '원문',
                excerpt: '원문 발췌',
                sourceResourceId: '원천 리소스 ID',
                linkTypeId: '관계 유형 ID',
                conditions: '연결 조건',
              } as Record<string, string>
            )[key] ?? key}
          </dt>
          <dd className="min-w-0 whitespace-pre-wrap break-words">
            {entry === null ? (
              '제공되지 않음'
            ) : typeof entry === 'object' ? (
              <pre className="overflow-x-auto rounded bg-slate-50 p-3 text-xs">
                {JSON.stringify(entry, null, 2)}
              </pre>
            ) : (
              String(entry)
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}

export default function ProposalDetail() {
  const { id } = useParams()
  const { search } = useLocation()
  const proposalId = Number(id)
  const validId =
    /^\d+$/.test(id ?? '') && Number.isSafeInteger(proposalId) && proposalId > 0
  const query = useQuery({
    queryKey: ['proposals', 'detail', proposalId],
    queryFn: ({ signal }) => fetchProposal(proposalId, signal),
    enabled: validId,
    retry: false,
  })
  let content
  if (!validId) content = <EmptyState message="올바른 제안 주소가 아닙니다." />
  else if (query.isPending)
    content = <LoadingState message="제안 상세를 불러오는 중…" />
  else if (query.isError)
    content = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else {
    const item = query.data
    content = (
      <article className="space-y-6">
        <header className="space-y-3">
          <p className="text-sm text-slate-500">
            제안 #{item.id} · 개정 {item.revision}
          </p>
          <h2 className="text-xl font-semibold">{proposalTitle(item)}</h2>
          <div className="flex flex-wrap gap-2">
            <StatusBadge>
              {kindLabels[item.itemKind] ?? item.itemKind}
            </StatusBadge>
            <StatusBadge>
              {originLabels[item.origin] ?? item.origin}
            </StatusBadge>
            <StatusBadge>
              {reviewLabels[item.reviewStatus] ?? item.reviewStatus}
            </StatusBadge>
            <StatusBadge
              tone={item.applyStatus === 'FAILED' ? 'danger' : 'neutral'}
            >
              {applyLabels[item.applyStatus] ?? item.applyStatus}
            </StatusBadge>
          </div>
        </header>
        <section className="rounded-xl border border-slate-200 p-5">
          <h3 className="font-semibold">AI 신뢰도</h3>
          <p className="mt-2 text-2xl font-semibold text-violet-700">
            {confidence(item.aiConfidence)}
          </p>
          <p className="mt-2 text-sm text-slate-500">
            AI가 제안한 신뢰도이며, 정합성 검사 통과나 승인 여부를 뜻하지
            않습니다.
          </p>
          {item.analysisRunId !== null && (
            <p className="mt-2 text-sm">분석 실행 #{item.analysisRunId}</p>
          )}
        </section>
        <section className="rounded-xl border border-slate-200 p-5">
          <h3 className="font-semibold">제안 내용</h3>
          <RecordView value={item.payload} />
        </section>
        <section className="rounded-xl border border-slate-200 p-5">
          <h3 className="font-semibold">제안 근거</h3>
          <RecordView value={item.evidence} />
        </section>
        <section className="rounded-xl border border-slate-200 p-5">
          <h3 className="font-semibold">최근 정합성 검사</h3>
          {item.latestCheck ? (
            <p className="mt-2 text-sm">
              검사 #{item.latestCheck.checkRunId} ·{' '}
              {(
                {
                  SUCCEEDED: '완료',
                  FAILED: '실패',
                  QUEUED: '대기',
                  RUNNING: '실행 중',
                } as Record<string, string>
              )[item.latestCheck.status] ?? item.latestCheck.status}
            </p>
          ) : (
            <p className="mt-2 text-sm text-slate-500">
              아직 검사 결과가 없습니다.
            </p>
          )}
        </section>
        {item.applyError && (
          <p role="alert" className="rounded-lg bg-red-50 p-4 text-red-800">
            반영 실패 사유: {item.applyError}
          </p>
        )}
      </article>
    )
  }
  return (
    <div className="space-y-5">
      <Link
        to={`/admin/rules${search}`}
        className="text-sm text-violet-700 underline"
      >
        제안 목록으로 돌아가기
      </Link>
      <h1 className="text-2xl font-semibold">제안 상세</h1>
      {content}
    </div>
  )
}
