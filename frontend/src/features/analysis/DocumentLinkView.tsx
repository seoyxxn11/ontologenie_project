import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import type { Proposal } from '../review/types'
import {
  assertionLabels,
  expressionLabels,
  markLabels,
  fetchDocumentLinkOptions,
  readDocumentLink,
  splitOriginal,
  targetName,
} from './documentLink'

function Candidates({ proposal }: { proposal: Proposal }) {
  const query = useQuery({
    queryKey: ['proposals', 'options', proposal.id, proposal.revision],
    queryFn: ({ signal }) => fetchDocumentLinkOptions(proposal, signal),
    retry: false,
  })
  if (query.isPending)
    return <LoadingState message="후보 설비를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  if (query.data.length < 2) return null
  const labels = readDocumentLink(proposal).targetLabels
  return (
    <div className="space-y-2">
      <p className="font-medium text-slate-900">
        {`복수 후보 ${query.data.length}개 · 하나로 정하지 못했습니다`}
      </p>
      <ul aria-label="후보 대상" className="flex flex-wrap gap-2">
        {query.data.map((option) => (
          <li
            key={option.id}
            className="rounded-lg border border-slate-200 px-3 py-1"
          >
            {`${option.optionNo}. ${targetName(
              typeof option.payload.targetObjectRef === 'string'
                ? option.payload.targetObjectRef
                : undefined,
              labels,
            )}`}
          </li>
        ))}
      </ul>
    </div>
  )
}

// 문서 연결(DOCUMENT_LINK) 후보의 원문과 추출 결과 비교
function DocumentLinkView({ proposal }: { proposal: Proposal }) {
  const link = readDocumentLink(proposal)
  // 보류 권고는 아직 검토 전일 때만 미확정이다. 승인·거절한 뒤에는 결정이 났다.
  const undecided =
    proposal.reviewStatus === 'HELD' ||
    (proposal.reviewStatus === 'PENDING' && link.holdRecommended)

  return (
    <section
      aria-label="정보 추출 결과"
      className="space-y-4 rounded-xl border border-slate-200 p-5 text-sm"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-semibold">정보 추출 결과</h3>
        {link.expressionKind && (
          <StatusBadge
            tone={link.expressionKind === 'ESTIMATED' ? 'warning' : 'success'}
          >
            {expressionLabels[link.expressionKind]}
          </StatusBadge>
        )}
        {undecided && <StatusBadge tone="warning">미확정</StatusBadge>}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <p className="font-medium text-slate-600">
            원문
            {link.documentLabel
              ? ` · ${link.documentLabel}${link.documentId ? ` (${link.documentId})` : ''}`
              : link.documentId
                ? ` · ${link.documentId}`
                : ''}
          </p>
          {link.originalText ? (
            <p
              aria-label="원문"
              className="whitespace-pre-wrap rounded-lg bg-slate-50 p-3 leading-7"
            >
              {splitOriginal(link).map((segment, index) =>
                segment.mark === undefined ? (
                  <span key={index}>{segment.text}</span>
                ) : (
                  <mark
                    key={index}
                    title={markLabels[segment.mark].label}
                    className={`rounded px-0.5 ${markLabels[segment.mark].className}`}
                  >
                    {segment.text}
                  </mark>
                ),
              )}
            </p>
          ) : (
            <p className="text-slate-500">원문이 제공되지 않았습니다.</p>
          )}
        </div>

        <dl className="divide-y divide-slate-100">
          <div className="grid grid-cols-[96px_1fr] gap-2 py-2">
            <dt className="text-slate-600">언급</dt>
            <dd className="font-medium">{link.mentionText ?? '-'}</dd>
          </div>
          <div className="grid grid-cols-[96px_1fr] gap-2 py-2">
            <dt className="text-slate-600">연결 대상</dt>
            <dd className="font-medium">
              {undecided
                ? '미확정'
                : targetName(link.targetObjectRef, link.targetLabels)}
            </dd>
          </div>
          {link.observations.length > 0 && (
            <div className="grid grid-cols-[96px_1fr] gap-2 py-2">
              <dt className="text-slate-600">관찰 표현</dt>
              <dd>
                <ul aria-label="관찰 표현" className="space-y-1">
                  {link.observations.map((item) => (
                    <li key={`${item.spanStart}-${item.spanEnd}`}>
                      <StatusBadge
                        tone={
                          item.assertion === 'NEGATED'
                            ? 'neutral'
                            : item.assertion === 'UNCERTAIN'
                              ? 'warning'
                              : 'success'
                        }
                      >
                        {assertionLabels[item.assertion].label}
                      </StatusBadge>{' '}
                      {item.text}
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          )}
          {link.times.length > 0 && (
            <div className="grid grid-cols-[96px_1fr] gap-2 py-2">
              <dt className="text-slate-600">시각</dt>
              <dd>
                <ul aria-label="시각 표현" className="space-y-1">
                  {link.times.map((item) => (
                    <li key={`${item.spanStart}-${item.spanEnd}`}>
                      {item.text} →{' '}
                      {item.normalized
                        ? formatKstDateTime(item.normalized)
                        : '정하지 않음'}
                      {item.basis && (
                        <span className="text-xs text-slate-500">
                          {' '}
                          ({item.basis})
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          )}
          {undecided && link.holdReason && (
            <div className="grid grid-cols-[96px_1fr] gap-2 py-2">
              <dt className="text-slate-600">미확정 이유</dt>
              <dd>{link.holdReason}</dd>
            </div>
          )}
        </dl>
      </div>

      <Candidates proposal={proposal} />
    </section>
  )
}

export default DocumentLinkView
