import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchProposal, fetchProposalOptions } from './api'
import { confidence, proposalTitle } from './labels'
import { isRecord } from '../../api/errors'
import { transformLabels } from './ruleDraft'
import type { Proposal, ProposalOption } from './types'

function Content({ value }: { value: Record<string, unknown> }) {
  if (!Object.keys(value).length) return <p>제공되지 않음</p>
  return (
    <pre className="overflow-x-auto whitespace-pre-wrap break-words rounded-xl bg-slate-50 p-4 text-sm">
      {JSON.stringify(value, null, 2)}
    </pre>
  )
}

function Evidence({ value }: { value: Record<string, unknown> }) {
  const labels: Record<string, string> = {
    reason: '제안 이유',
    originalText: '원문',
    sourceText: '원문',
    excerpt: '원문 발췌',
  }
  const entries = Object.entries(value)
  if (!entries.length) return <p>제공되지 않음</p>
  return (
    <dl className="space-y-3">
      {entries.map(([key, entry]) => (
        <div key={key}>
          <dt className="text-sm text-slate-500">{labels[key] ?? key}</dt>
          <dd className="whitespace-pre-wrap break-words">
            {entry == null ? (
              '제공되지 않음'
            ) : typeof entry === 'object' ? (
              <Content value={{ [key]: entry }} />
            ) : (
              String(entry)
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}

function transforms(value: unknown) {
  if (!Array.isArray(value) || !value.length) return '그대로 연결'
  return value
    .map((step) =>
      isRecord(step) && typeof step.op === 'string'
        ? `${transformLabels[step.op] ?? step.op}${step.arg == null ? '' : ` (${typeof step.arg === 'object' ? JSON.stringify(step.arg) : String(step.arg)})`}`
        : '알 수 없는 변환',
    )
    .join(' → ')
}

function RuleContent({ value }: { value: Record<string, unknown> }) {
  const conditions = Array.isArray(value.conditions)
    ? value.conditions.filter(isRecord)
    : []
  return (
    <>
      {typeof value.description === 'string' && (
        <p className="mb-3">{value.description}</p>
      )}
      {conditions.length ? (
        <div className="overflow-x-auto">
          <table
            className="w-full text-left text-sm"
            aria-label="연결·변환 규칙"
          >
            <thead>
              <tr>
                <th className="p-2">출발 속성</th>
                <th className="p-2">변환 방식</th>
                <th className="p-2">대상 속성</th>
              </tr>
            </thead>
            <tbody>
              {conditions.map((condition, position) => (
                <tr key={position} className="border-t border-slate-200">
                  <td className="p-2">
                    {String(condition.sourceProperty ?? '제공되지 않음')}
                  </td>
                  <td className="p-2">
                    출발: {transforms(condition.sourceTransforms)}
                    <br />
                    대상: {transforms(condition.targetTransforms)}
                    <br />
                    비교: {String(condition.operator ?? '제공되지 않음')}
                  </td>
                  <td className="p-2">
                    {String(condition.targetProperty ?? '제공되지 않음')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Content value={value} />
      )}
      {conditions.length > 0 && (
        <details className="mt-3">
          <summary>전체 규칙 내용</summary>
          <Content value={value} />
        </details>
      )}
    </>
  )
}

function ComparisonDialog({
  proposal,
  onClose,
}: {
  proposal: Proposal
  onClose: () => void
}) {
  const [index, setIndex] = useState(0)
  const [reason, setReason] = useState('')
  const query = useQuery({
    queryKey: ['proposals', 'comparison', proposal.id, proposal.revision],
    queryFn: async ({ signal }) => {
      const latest = await fetchProposal(proposal.id, signal)
      const options = await fetchProposalOptions(
        latest.id,
        latest.revision,
        signal,
      )
      return { latest, options }
    },
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  let content
  if (query.isPending || query.isFetching || query.isPaused)
    content = <LoadingState message="최신 개정의 후보를 불러오는 중…" />
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
    const { latest, options } = query.data
    const option: ProposalOption | undefined =
      options[Math.min(index, options.length - 1)]
    content = (
      <div className="mt-5 space-y-5">
        <p>
          {proposalTitle(latest)} · 개정 {latest.revision}
        </p>
        {latest.revision !== proposal.revision && (
          <p role="status" className="rounded-lg bg-amber-50 p-3">
            제안이 변경되어 최신 개정 {latest.revision}의 후보를 표시합니다.
          </p>
        )}
        <p className="text-sm text-slate-600">
          이전·다음 화살표로 후보를 비교하세요. 후보 선택은 별도 승인을 거쳐
          반영됩니다.
        </p>
        {!option ? (
          <EmptyState message="이 개정에 비교할 대안 후보가 없습니다." />
        ) : (
          <>
            <nav
              aria-label="관계 대안 후보 이동"
              className="flex items-center justify-between gap-3"
            >
              <Button
                variant="secondary"
                disabled={index === 0}
                onClick={() => {
                  setIndex(index - 1)
                  setReason('')
                }}
              >
                ← 이전 후보
              </Button>
              <span role="status">
                후보 {Math.min(index + 1, options.length)} / {options.length}
              </span>
              <Button
                variant="secondary"
                disabled={index >= options.length - 1}
                onClick={() => {
                  setIndex(index + 1)
                  setReason('')
                }}
              >
                다음 후보 →
              </Button>
            </nav>
            <article className="space-y-4 rounded-2xl border border-violet-200 bg-violet-50/30 p-5">
              <h3 className="text-lg font-semibold">
                {typeof option.payload.name === 'string'
                  ? option.payload.name
                  : `대안 후보 ${option.optionNo}`}
              </h3>
              <p className="text-sm text-slate-600">
                후보 번호 {option.optionNo} · 개정 {option.revision}
              </p>
              <section>
                <h4 className="mb-2 font-semibold">제안 이유·원본</h4>
                <Evidence value={option.evidence} />
              </section>
              <section>
                <h4 className="font-semibold">AI 신뢰도</h4>
                <p className="text-2xl font-semibold text-violet-700">
                  {confidence(option.aiConfidence)}
                </p>
              </section>
              <section>
                <h4 className="mb-2 font-semibold">규칙 내용</h4>
                <RuleContent value={option.payload} />
              </section>
              <section>
                <h4 className="font-semibold">검사 결과</h4>
                <p className="mt-2 text-sm">
                  개별 대안의 검사 결과는 아직 제공되지 않았습니다.
                </p>
              </section>
              <div className="flex flex-wrap gap-3">
                <Button disabled>이 후보 선택</Button>
                <Button variant="secondary" disabled>
                  이 후보 보류
                </Button>
              </div>
            </article>
            <label className="block space-y-2">
              <span>선택·보류 사유</span>
              <textarea
                className="w-full rounded-xl border border-slate-300 p-3"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="현재 후보를 선택하거나 판단을 보류하는 이유"
              />
            </label>
            <p role="status" className="text-sm text-slate-600">
              선택·보류 저장 기능은 준비 중입니다. 입력한 사유는 저장되지 않으며
              창을 닫으면 사라집니다.
            </p>
            <Button variant="secondary" disabled>
              이 관계 판단 보류
            </Button>
          </>
        )}
        <section className="rounded-xl border border-slate-200 p-4">
          <h3 className="font-semibold">제안 개정의 최근 검사</h3>
          <p className="mt-2 text-sm text-slate-600">
            아래는 제안 개정 전체의 정보이며, 보고 있는 개별 대안의 검사 결과가
            아닙니다.
          </p>
          {latest.latestCheck ? (
            <p className="mt-2">
              검사 #{latest.latestCheck.checkRunId} ·{' '}
              {(
                {
                  SUCCEEDED: '완료',
                  FAILED: '실패',
                  QUEUED: '대기',
                  RUNNING: '실행 중',
                } as Record<string, string>
              )[latest.latestCheck.status] ?? latest.latestCheck.status}
            </p>
          ) : (
            <p className="mt-2">아직 검사 결과가 없습니다.</p>
          )}
        </section>
      </div>
    )
  }
  return (
    <Dialog title="대안 후보 비교" onClose={onClose}>
      {content}
    </Dialog>
  )
}

export default function ProposalComparison({
  proposal,
}: {
  proposal: Proposal
}) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>
        대안 후보 비교
      </Button>
      {open && (
        <ComparisonDialog
          key={`${proposal.id}:${proposal.revision}`}
          proposal={proposal}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}
