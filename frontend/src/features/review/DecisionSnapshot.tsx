import { useQuery } from '@tanstack/react-query'
import Dialog from '../../components/Dialog'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { ApiError, isRecord } from '../../api/errors'
import { fetchProposalOptions } from './api'
import { fetchHistoricalRevision } from './historyApi'
import RuleContent from './RuleContent'
import type { Proposal } from './types'
import type { ProposalDecisionRecord } from './decisionApi'

export function DecisionOptionLink({
  record,
  onOpen,
}: {
  proposalId: number
  record: ProposalDecisionRecord
  onOpen: () => void
}) {
  return (
    <button
      type="button"
      className="history-detail-link"
      aria-haspopup="dialog"
      onClick={onOpen}
    >
      {record.selectedOptionNo
        ? `대안 ${record.selectedOptionNo}`
        : '대안 보기'}
    </button>
  )
}

export default function DecisionSnapshot({
  proposal,
  record,
  kind,
  onClose,
}: {
  proposal: Proposal
  record: ProposalDecisionRecord
  kind: 'revision' | 'option'
  onClose: () => void
}) {
  const query = useQuery({
    queryKey: [
      'proposals',
      'decisionSnapshot',
      proposal.id,
      record.itemRevision,
      kind,
      record.selectedOptionId,
    ],
    queryFn: async ({ signal }) => {
      if (kind === 'revision')
        return fetchHistoricalRevision(proposal.id, record.itemRevision, signal)
      const options = await fetchProposalOptions(
        proposal.id,
        record.itemRevision,
        signal,
      )
      const option = options.find((item) => item.id === record.selectedOptionId)
      if (!option)
        throw new ApiError('해당 개정의 선택 대안을 찾을 수 없습니다.', {
          kind: 'invalid-response',
        })
      if (!isRecord(option.payload) || !isRecord(option.evidence))
        throw new ApiError('선택 대안의 내용을 확인할 수 없습니다.', {
          kind: 'invalid-response',
        })
      return option
    },
    retry: false,
  })
  return (
    <Dialog
      title={kind === 'revision' ? '당시 규칙 내용' : '당시 선택 대안'}
      size="wide"
      onClose={onClose}
    >
      <div className="history-dialog-content">
        <p className="history-muted">
          제안 #{proposal.id} · 개정 {record.itemRevision} · 결정 #{record.id}
        </p>
        {query.isPending ? (
          <LoadingState message="당시 검토 내용을 불러오는 중…" />
        ) : query.isError ? (
          <ErrorState
            error={query.error}
            onRetry={() => {
              void query.refetch()
            }}
          />
        ) : (
          <>
            {typeof query.data.payload.name === 'string' && (
              <h3>{query.data.payload.name}</h3>
            )}
            {'optionNo' in query.data && <h3>대안 {query.data.optionNo}</h3>}
            <RuleContent
              proposal={{
                ...proposal,
                revision: record.itemRevision,
                payload: query.data.payload,
                evidence: query.data.evidence,
              }}
            />
            <details>
              <summary>근거·원본 내용</summary>
              <pre>
                {JSON.stringify(
                  {
                    payload: query.data.payload,
                    evidence: query.data.evidence,
                  },
                  null,
                  2,
                )}
              </pre>
            </details>
          </>
        )}
      </div>
    </Dialog>
  )
}
