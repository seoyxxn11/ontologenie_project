import { useId, useState } from 'react'
import Button from '../../components/Button'
import type { Proposal } from './types'

const checkLabels: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
}

// 실제 결정·반영 API가 제공되기 전의 입력 화면이다. 쓰기 요청이나 성공 상태를 만들지 않는다.
export default function ProposalDecision({
  proposal,
  unavailable = false,
}: {
  proposal: Proposal
  unavailable?: boolean
}) {
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const hintId = useId()
  const errorId = useId()
  const error = touched && !reason.trim() ? '검토 사유를 입력해 주세요.' : null
  const editable = ['PENDING', 'HELD'].includes(proposal.reviewStatus)
  return (
    <section
      className="space-y-4 rounded-xl border border-violet-200 bg-violet-50/20 p-5"
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
              disabled={unavailable}
              aria-invalid={!!error}
              aria-describedby={`${hintId}${error ? ` ${errorId}` : ''}`}
              onChange={(event) => setReason(event.target.value)}
              onBlur={() => setTouched(true)}
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
          <div className="flex flex-wrap gap-3">
            <Button disabled>승인</Button>
            <Button variant="secondary" disabled>
              이 후보 보류
            </Button>
            <Button variant="secondary" disabled>
              거절
            </Button>
          </div>
          <p className="text-sm text-slate-600">
            ‘이 후보 보류’는 제안 전체에 대한 판단 보류입니다. 검사에서 발견한
            개별 문제 건의 보류와 구분됩니다.
          </p>
        </>
      )}
      <p className="text-sm text-slate-600">
        {proposal.latestCheck
          ? `최근 검사 #${proposal.latestCheck.checkRunId}: ${checkLabels[proposal.latestCheck.status] ?? proposal.latestCheck.status}.`
          : '아직 검사 이력이 없습니다.'}{' '}
        실제 승인 가능 여부는 같은 개정의 검사 결과를 기준으로 서버에서 최종
        확인합니다. AI 신뢰도만으로 승인하지 않습니다.
      </p>
      <p
        role="status"
        className="rounded-lg bg-white p-3 text-sm text-slate-600"
      >
        결정 저장 기능은 준비 중입니다. 버튼을 사용할 수 없으며, 입력한 사유는
        저장되지 않습니다. 화면을 떠나거나 제안 개정·검토 상태가 바뀌면
        사라집니다.
      </p>
    </section>
  )
}
