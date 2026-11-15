import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams, useLocation } from 'react-router'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import PageHeader from '../../components/PageHeader'
import DocumentLinkView from '../analysis/DocumentLinkView'
import { expressionLabels } from '../analysis/documentLink'
import { cardinalityLabels } from '../explorer/labels'
import {
  BUNDLE_EVIDENCE_KEYS,
  BUNDLE_PAYLOAD_KEYS,
  conditionText,
  isBundle,
  isRow,
  withoutBundleKeys,
} from './bundle'
import { CHECK_POLL_MS, shouldPoll } from './checkPolling'
import EvidenceView from './EvidenceView'
import ProposalBundleView from './ProposalBundleView'
import ProposalComparison from './ProposalComparison'
import CheckResults from './CheckResults'
import ProposalDecision from './ProposalDecision'
import RuleContent from './RuleContent'
import { fetchProposal } from './api'
import type { ProposalOption } from './types'
import {
  applyLabels,
  confidence,
  kindLabels,
  originLabels,
  proposalTitle,
  reviewLabels,
} from './labels'

// 문서 연결은 기본 화면의 "정보 추출 결과"에서 보여 준 값을 다시 늘어놓지 않는다.
const DOCUMENT_SHOWN_PAYLOAD = ['spanStart', 'spanEnd']
const DOCUMENT_SHOWN_EVIDENCE = ['originalText', 'observations', 'times']

const recordKeyLabels: Record<string, string> = {
  reason: '제안 이유',
  name: '이름',
  code: '코드',
  displayPropertyCode: '대표 속성',
  description: '설명',
  originalText: '원문',
  sourceText: '원문',
  excerpt: '원문 발췌',
  sourceResourceId: '원천 리소스 ID',
  linkTypeId: '관계 유형 ID',
  conditions: '연결 조건',
  note: '메모',
  category: '표 종류',
  synonyms: '다른 이름',
  sourceTypeCode: '출발 객체 유형',
  targetTypeCode: '대상 객체 유형',
  cardinality: '개수 관계',
  linkTypeCode: '관계 유형 코드',
  documentId: '문서 번호',
  mentionText: '언급한 글자',
  targetObjectRef: '연결 대상',
  expressionKind: '표현',
  pattern: '비교 방식',
  sourceFilter: '적용 조건',
}

// 원본 값 그대로면 알아보기 어려운 코드 값
const recordValueLabels: Record<string, Record<string, string>> = {
  pattern: { same_value: '값 그대로 비교', transform: '변환 후 비교' },
  category: {
    master: '기준 정보',
    transaction: '거래 문서',
    detail: '문서 상세 줄',
    history: '발생 기록',
    snapshot: '현재 상태',
  },
  cardinality: cardinalityLabels,
  expressionKind: expressionLabels,
}

function isEmptyEntry(entry: unknown) {
  return (
    entry === null ||
    entry === undefined ||
    entry === '' ||
    (Array.isArray(entry) && entry.length === 0)
  )
}

function RecordView({ value }: { value: Record<string, unknown> }) {
  // 빈 칸(메모 없음·다른 이름 없음)은 보여 주지 않는다
  const entries = Object.entries(value).filter(
    ([, entry]) => !isEmptyEntry(entry),
  )
  if (!entries.length)
    return <EmptyState message="정보가 제공되지 않았습니다." />
  return (
    <dl className="candidate-records divide-y divide-slate-100">
      {entries.map(([key, entry]) => (
        <div key={key} className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="break-words font-medium text-slate-600">
            {recordKeyLabels[key] ?? key}
          </dt>
          <dd className="min-w-0 whitespace-pre-wrap break-words">
            {key === 'conditions' && Array.isArray(entry) ? (
              <ul className="space-y-1 font-mono text-xs">
                {entry.filter(isRow).map((condition, index) => (
                  <li key={index}>{conditionText(condition)}</li>
                ))}
              </ul>
            ) : Array.isArray(entry) &&
              entry.every((item) => typeof item === 'string') ? (
              entry.join(', ')
            ) : typeof entry === 'object' ? (
              <pre className="overflow-x-auto rounded bg-slate-50 p-3 text-xs">
                {JSON.stringify(entry, null, 2)}
              </pre>
            ) : (
              (recordValueLabels[key]?.[String(entry)] ?? String(entry))
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}

export default function ProposalDetail({
  embeddedId,
}: { embeddedId?: number } = {}) {
  const { id } = useParams()
  const { search } = useLocation()
  const proposalId = embeddedId ?? Number(id)
  const validId =
    (embeddedId !== undefined || /^\d+$/.test(id ?? '')) &&
    Number.isSafeInteger(proposalId) &&
    proposalId > 0
  const [refreshing, setRefreshing] = useState(false)
  const [selection, setSelection] = useState<{
    key: string
    option: ProposalOption
  }>()
  const queryClient = useQueryClient()
  const queryKey = ['proposals', 'detail', proposalId]
  // 화면을 연 시점의 조회 횟수. 같은 제안에 다시 들어와도 그때부터 다시 센다(캐시에 남은 횟수 제외).
  const [startCount] = useState(
    () => queryClient.getQueryState(queryKey)?.dataUpdateCount ?? 0,
  )
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchProposal(proposalId, signal),
    enabled: validId,
    retry: false,
    refetchOnMount: 'always',
    refetchInterval: (query) =>
      shouldPoll(
        query.state.data,
        query.state.dataUpdateCount - startCount,
        !!query.state.error,
      )
        ? CHECK_POLL_MS
        : false,
  })
  async function refreshProposal() {
    setRefreshing(true)
    try {
      return (await query.refetch()).isSuccess
    } finally {
      setRefreshing(false)
    }
  }
  // 같은 내용이 와도 확인할 때마다 다시 그리도록 dataUpdatedAt을 읽는다.
  const polling =
    query.dataUpdatedAt > 0 &&
    shouldPoll(
      query.data,
      (queryClient.getQueryState(queryKey)?.dataUpdateCount ?? 0) - startCount,
      !!query.error,
    )
  let content
  if (!validId) content = <EmptyState message="올바른 제안 주소가 아닙니다." />
  else if (query.isPending)
    content = <LoadingState message="제안 상세를 불러오는 중…" />
  else if (query.isError && !query.data)
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
    const selectionKey = `${item.id}:${item.revision}:${item.reviewStatus}`
    const unavailable =
      refreshing || !query.isFetchedAfterMount || !!query.error
    const editParams = new URLSearchParams(search)
    editParams.set('candidate', String(item.id))
    const historyUrl = `/admin/rules/${item.id}/history`
    const activityParams = new URLSearchParams(search)
    activityParams.set('view', 'activity')
    content = (
      <article className="candidate-detail">
        <header className="candidate-heading">
          <p className="text-sm text-slate-500">
            {String(item.payload.code ?? `#${item.id}`)} · 개정 {item.revision}{' '}
            · {kindLabels[item.itemKind] ?? item.itemKind} ·{' '}
            {originLabels[item.origin] ?? item.origin}
          </p>
          <div className="candidate-title-line">
            <h2 className="text-xl font-semibold">{proposalTitle(item)}</h2>
            <div className="candidate-header-actions">
              {item.itemKind === 'LINK_RULE' &&
                ['PENDING', 'HELD'].includes(item.reviewStatus) && (
                  <Link
                    className="candidate-history"
                    to={`/admin/rules/${item.id}/edit?${editParams}`}
                  >
                    규칙 후보 수정
                  </Link>
                )}
              <Link className="candidate-history" to={`${historyUrl}${search}`}>
                이력
              </Link>
            </div>
          </div>
          <p className="candidate-state-line">
            <span>
              검토 상태:{' '}
              <StatusBadge
                tone={
                  item.reviewStatus === 'HELD' ||
                  item.reviewStatus === 'PENDING'
                    ? 'warning'
                    : item.reviewStatus === 'APPROVED'
                      ? 'info'
                      : 'neutral'
                }
              >
                {reviewLabels[item.reviewStatus] ?? item.reviewStatus}
              </StatusBadge>
            </span>
            <span>
              적용 여부:{' '}
              <StatusBadge
                tone={
                  item.applyStatus === 'FAILED'
                    ? 'danger'
                    : item.applyStatus === 'APPLIED'
                      ? 'success'
                      : 'neutral'
                }
              >
                {applyLabels[item.applyStatus] ?? item.applyStatus}
              </StatusBadge>
            </span>
          </p>
        </header>
        {item.applyError && (
          <p role="alert" className="rounded-lg bg-red-50 p-4 text-red-800">
            반영 실패 사유: {item.applyError}
          </p>
        )}
        <div className="candidate-rule-section space-y-6">
          {item.itemKind === 'DOCUMENT_LINK' ? (
            <DocumentLinkView proposal={item} />
          ) : (
            embeddedId && <RuleContent proposal={item} />
          )}
          <details open={!embeddedId} className="candidate-definition">
            <summary>
              {embeddedId ? '후보 상세 정의·대안 보기' : '상세 정의'}
            </summary>
            <ProposalComparison
              proposal={item}
              onSelect={
                unavailable
                  ? undefined
                  : (option) => setSelection({ key: selectionKey, option })
              }
            />
            {!embeddedId && (
              <section className="candidate-confidence-section">
                <h3 className="font-semibold">AI 신뢰도</h3>
                <p className="mt-2 text-sm font-medium text-slate-700">
                  {confidence(item.aiConfidence)}
                </p>
                <p className="mt-2 text-sm text-slate-500">
                  AI가 제안한 신뢰도이며, 정합성 검사 통과나 승인 여부를 뜻하지
                  않습니다.
                </p>
                {item.analysisRunId !== null && (
                  <p className="mt-2 text-sm">
                    분석 실행 #{item.analysisRunId}
                  </p>
                )}
              </section>
            )}
            <ProposalBundleView proposal={item} />
            <section className="rounded-xl border border-slate-200 p-5">
              <h3 className="font-semibold">제안 내용</h3>
              <RecordView
                value={
                  isBundle(item)
                    ? withoutBundleKeys(item.payload, BUNDLE_PAYLOAD_KEYS)
                    : item.itemKind === 'DOCUMENT_LINK'
                      ? withoutBundleKeys(item.payload, DOCUMENT_SHOWN_PAYLOAD)
                      : item.payload
                }
              />
            </section>
          </details>
        </div>
        <div className={embeddedId ? 'candidate-review-context' : 'space-y-6'}>
          <section className="candidate-evidence" aria-label="제안 근거">
            <h3 className="font-semibold">제안 근거</h3>
            {embeddedId && (
              <div className="candidate-confidence-row">
                <span className="candidate-confidence">
                  신뢰도: {confidence(item.aiConfidence)}
                </span>
                <span>AI 제안 지표 · 검사 통과·승인 여부와 별도</span>
                {item.analysisRunId !== null && (
                  <span>분석 실행 #{item.analysisRunId}</span>
                )}
              </div>
            )}
            <EvidenceView
              evidence={
                isBundle(item)
                  ? withoutBundleKeys(item.evidence, BUNDLE_EVIDENCE_KEYS)
                  : item.itemKind === 'DOCUMENT_LINK'
                    ? withoutBundleKeys(item.evidence, DOCUMENT_SHOWN_EVIDENCE)
                    : item.evidence
              }
            />
          </section>
          <CheckResults
            compact={!!embeddedId}
            proposal={item}
            refreshing={refreshing}
            polling={polling}
            refreshError={query.error}
            onRefresh={() => {
              void refreshProposal()
            }}
          />
        </div>
        <ProposalDecision
          key={item.id}
          proposal={item}
          unavailable={unavailable}
          selectedOption={
            selection?.key === selectionKey ? selection.option : undefined
          }
          onClearSelection={() => setSelection(undefined)}
          onRefresh={refreshProposal}
          historyUrl={`${historyUrl}?${activityParams}`}
        />
      </article>
    )
  }
  if (embeddedId)
    return (
      <section className="candidate-detail-region" aria-label="후보 검토 상세">
        {content}
      </section>
    )
  return (
    <div className="rules-detail-page">
      <Link
        to={`/admin/rules${search}`}
        className="text-sm text-violet-700 underline"
      >
        제안 목록으로 돌아가기
      </Link>
      <PageHeader
        title="제안 상세"
        description="규칙 내용과 근거·검사 결과를 확인하고 검토 결정을 남기세요."
      />
      {content}
    </div>
  )
}
