import {
  useIsMutating,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import {
  saveProposalDecision,
  type Decision,
  type DecisionInput,
} from './decisionApi'
import {
  applyLabels,
  candidateCheckLabel,
  CHECK_NOT_REQUIRED_MESSAGE,
  requiresCandidateCheck,
  reviewLabels,
} from './labels'
import type { Proposal, ProposalOption } from './types'

const actions = [
  ['APPROVE', '승인'],
  ['HOLD', '이 후보 보류'],
  ['REJECT', '거절'],
] as const

export default function ProposalDecision({
  proposal,
  unavailable = false,
  selectedOption,
  onClearSelection,
  onRefresh,
  historyUrl,
}: {
  proposal: Proposal
  unavailable?: boolean
  selectedOption?: Pick<ProposalOption, 'id' | 'optionNo'>
  onClearSelection: () => void
  onRefresh: () => Promise<boolean>
  historyUrl: string
}) {
  const draftKey = `${proposal.revision}:${proposal.reviewStatus}`
  const [draft, setDraft] = useState({
    key: draftKey,
    reason: '',
    touched: false,
  })
  const { reason, touched } =
    draft.key === draftKey ? draft : { reason: '', touched: false }
  const hintId = useId()
  const errorId = useId()
  const error = touched && !reason.trim() ? '검토 사유를 입력해 주세요.' : null
  const editable = ['PENDING', 'HELD'].includes(proposal.reviewStatus)
  const client = useQueryClient()
  const mutationKey = ['proposal-decision', proposal.id]
  const saving = useIsMutating({ mutationKey }) > 0
  const mutation = useMutation({
    mutationKey,
    mutationFn: (input: DecisionInput) =>
      saveProposalDecision(proposal.id, input),
    networkMode: 'always',
    retry: false,
    onSuccess: () => client.invalidateQueries({ queryKey: ['proposals'] }),
  })
  const apiError = mutation.error instanceof ApiError ? mutation.error : null
  const conflict = apiError?.kind === 'conflict'
  const ambiguous =
    apiError != null &&
    ['network', 'server', 'http', 'invalid-response'].includes(apiError.kind)
  const blocked =
    conflict ||
    ambiguous ||
    (apiError != null &&
      ['unauthenticated', 'forbidden', 'not-found'].includes(apiError.kind))
  const disabled = unavailable || saving || blocked
  const sameRevision = mutation.variables?.revision === proposal.revision
  const result = sameRevision ? mutation.data : undefined
  const failureReason = apiError?.details.reason

  async function submit(decision: Decision) {
    if (!editable || disabled || client.isMutating({ mutationKey })) return
    if (!reason.trim() || reason.length > 1000) return
    try {
      await mutation.mutateAsync({
        revision: proposal.revision,
        decision,
        reason: reason.trim(),
        ...(decision === 'APPROVE' && selectedOption
          ? { selectedOptionId: selectedOption.id }
          : {}),
      })
      setDraft({ key: draftKey, reason: '', touched: false })
      onClearSelection()
    } catch {
      // 결정 오류는 서버 응답을 기준으로 아래에 표시한다.
    }
  }

  return (
    <section
      className="proposal-decision space-y-4"
      aria-label="제안 검토 결정"
    >
      <h3 className="font-semibold">검토 결정</h3>
      <p className="text-sm text-slate-600">
        제안 #{proposal.id} · 개정 {proposal.revision}에 대한 결정입니다.
        승인·거절·보류에는 사유가 필요합니다.
      </p>
      {!editable ? (
        <p role="status">
          이미 결정됐거나 현재 결정할 수 없는 제안입니다. 새 결정을 저장할 수
          없습니다.
        </p>
      ) : (
        <>
          {unavailable && (
            <p role="status">
              최신 제안 상태를 확인 중이거나 조회에 실패하여 입력할 수 없습니다.
            </p>
          )}
          <label className="block space-y-2">
            <span>검토 사유 (필수)</span>
            <textarea
              className="min-h-28 w-full rounded-xl border border-slate-300 bg-white p-3 disabled:bg-slate-100"
              value={reason}
              maxLength={1000}
              required
              disabled={disabled}
              aria-invalid={!!error}
              aria-describedby={`${hintId}${error ? ` ${errorId}` : ''}`}
              onChange={(event) =>
                setDraft({ key: draftKey, reason: event.target.value, touched })
              }
              onBlur={() => setDraft({ key: draftKey, reason, touched: true })}
              placeholder="승인·거절·보류하는 이유를 입력하세요"
            />
          </label>
          <p id={hintId} className="text-sm text-slate-500">
            {reason.length} / 1000자 · 공백만 입력할 수 없습니다.
          </p>
          {error && (
            <p id={errorId} role="alert" className="text-sm text-red-700">
              {error}
            </p>
          )}
          {selectedOption ? (
            <div className="flex flex-wrap items-center gap-3 text-sm">
              <p role="status">
                승인할 대안 후보 {selectedOption.optionNo}번 (ID #
                {selectedOption.id})
              </p>
              <Button
                variant="secondary"
                disabled={disabled}
                onClick={onClearSelection}
              >
                후보 선택 해제
              </Button>
            </div>
          ) : (
            <p className="text-sm text-slate-600">
              대안 후보를 선택하지 않으면 제안의 기본 내용으로 승인합니다.
            </p>
          )}
          <div className="flex flex-wrap gap-3">
            {actions.map(([decision, label]) => (
              <Button
                key={decision}
                variant={decision === 'APPROVE' ? 'primary' : 'secondary'}
                pending={saving && mutation.variables?.decision === decision}
                disabled={disabled || !reason.trim() || reason.length > 1000}
                onClick={() => void submit(decision)}
              >
                {label}
              </Button>
            ))}
          </div>
          <p className="text-sm text-slate-600">
            ‘이 후보 보류’는 제안 전체에 대한 판단 보류입니다. 검사에서 발견한
            개별 문제 건의 보류와 구분됩니다.
          </p>
        </>
      )}
      {!requiresCandidateCheck(proposal) ? (
        <p className="text-sm text-slate-600">
          {CHECK_NOT_REQUIRED_MESSAGE} AI 신뢰도만으로 승인하지 않습니다.
        </p>
      ) : (
        <p className="text-sm text-slate-600">
          {proposal.latestCheck
            ? `최근 검사 #${proposal.latestCheck.checkRunId}: ${candidateCheckLabel(proposal.latestCheck)}.`
            : '아직 검사 이력이 없습니다.'}{' '}
          실제 승인 가능 여부는 같은 개정의 검사 결과를 기준으로 서버에서 최종
          확인합니다. AI 신뢰도만으로 승인하지 않습니다.
        </p>
      )}
      {saving && <p role="status">검토 결정을 저장하는 중…</p>}
      {mutation.error && (
        <>
          <ErrorState error={mutation.error} />
          {typeof failureReason === 'string' && <p>{failureReason}</p>}
          {conflict && (
            <Button
              variant="secondary"
              onClick={async () => {
                if (await onRefresh()) {
                  onClearSelection()
                  mutation.reset()
                }
              }}
            >
              최신 제안 다시 확인
            </Button>
          )}
          {ambiguous && (
            <p>
              다시 결정하기 전에{' '}
              <Link className="text-violet-700 underline" to={historyUrl}>
                활동 기록
              </Link>
              에서 저장 여부를 확인해 주세요.
            </p>
          )}
        </>
      )}
      {result && (
        <div
          role="status"
          className="space-y-2 rounded-lg bg-emerald-50 p-3 text-sm"
        >
          <p>검토 결정을 저장했습니다. 결정 #{result.decisionId}</p>
          <p>
            검토: {reviewLabels[result.reviewStatus] ?? result.reviewStatus} ·
            반영: {applyLabels[result.applyStatus] ?? result.applyStatus}
          </p>
          <p>반영 대상 {result.appliedEntityIds.length}건</p>
        </div>
      )}
    </section>
  )
}
