import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import type { ObjectInstance } from '../explorer/types'
import IssueManualLinkTargetPicker from './IssueManualLinkTargetPicker'
import { createIssueManualLink, type Issue } from './api'

const errorMessages: Record<string, string> = {
  LINK_ALREADY_EXISTS: '이미 연결된 객체입니다. 기존 연결을 확인해 주세요.',
  LINK_SOURCE_LINKED_BY_RULE:
    '규칙이 이미 출발 객체를 연결했습니다. 최신 연결을 확인해 주세요.',
  LINK_END_TYPE_MISMATCH: '관계 유형에 맞는 도착 객체를 다시 선택해 주세요.',
  LINK_CARDINALITY_EXCEEDED:
    '허용된 연결 수를 초과합니다. 기존 연결을 확인해 주세요.',
  LINK_END_NOT_ACTIVE:
    '삭제됐거나 사용할 수 없는 객체입니다. 도착 객체를 다시 검색해 주세요.',
  LINK_SELF_REFERENCE: '출발 객체와 다른 도착 객체를 선택해 주세요.',
}

export default function IssueManualLinkForm({
  issue,
  unavailable = false,
  onPendingChange,
}: {
  issue: Issue
  unavailable?: boolean
  onPendingChange?: (pending: boolean) => void
}) {
  const queryClient = useQueryClient()
  const submitting = useRef(false)
  const [target, setTarget] = useState<ObjectInstance | null>(null)
  const [reason, setReason] = useState('')
  const [locked, setLocked] = useState(false)
  const [review, setReview] = useState<{
    target: ObjectInstance
    reason: string
    rowVersion: number
  } | null>(null)
  const mutation = useMutation({
    mutationFn: createIssueManualLink,
    retry: false,
    onSuccess: async () => {
      setReview(null)
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['issues', 'detail', issue.id],
        }),
        queryClient.invalidateQueries({ queryKey: ['issues', 'list'] }),
        queryClient.invalidateQueries({
          queryKey: ['issues', 'history', issue.id],
        }),
        queryClient.invalidateQueries({
          queryKey: ['issues', 'verifications', issue.id],
        }),
        queryClient.invalidateQueries({ queryKey: ['objects'] }),
        queryClient.invalidateQueries({ queryKey: ['audit'] }),
      ])
    },
    onError: (error) => {
      setReview(null)
      // 입력 수정이 가능한 400·422 외에는 처리 완료·응답 유실 가능성을 확인한다.
      if (!(error instanceof ApiError) || error.kind !== 'validation')
        setLocked(true)
      setTarget(null)
    },
    onSettled: () => {
      submitting.current = false
      onPendingChange?.(false)
    },
  })
  const eligible =
    issue.issueKind === 'UNMATCHED' &&
    issue.status === 'CHECKING' &&
    issue.objectInstanceId !== null &&
    issue.linkMappingId !== null &&
    Number.isSafeInteger(issue.rowVersion) &&
    issue.rowVersion >= 0
  const canReview =
    eligible &&
    !unavailable &&
    !locked &&
    !mutation.isSuccess &&
    target !== null &&
    Number.isSafeInteger(target.id) &&
    target.id > 0 &&
    target.id !== issue.objectInstanceId &&
    target.status === 'ACTIVE' &&
    reason.trim().length > 0 &&
    reason.length <= 1000
  const currentReview =
    review !== null && review.rowVersion === issue.rowVersion
  const error = mutation.error
  const displayError =
    error instanceof ApiError
      ? new ApiError(
          errorMessages[error.code ?? ''] ??
            (error.kind === 'validation'
              ? '입력 내용과 연결 조건을 확인하고 대상을 다시 선택해 주세요.'
              : '저장하지 못했거나 저장 결과를 확인할 수 없습니다. 재전송 전에 최신 문제·연결·실행 이력을 확인해 주세요.'),
          {
            kind: error.kind,
            status: error.status ?? undefined,
            requestId: error.requestId ?? undefined,
          },
        )
      : error

  function save() {
    if (
      !canReview ||
      !currentReview ||
      submitting.current ||
      mutation.isPending
    )
      return
    submitting.current = true
    onPendingChange?.(true)
    mutation.mutate({
      issueId: issue.id,
      targetObjectId: review.target.id,
      reason: review.reason,
      rowVersion: review.rowVersion,
    })
  }

  return (
    <section aria-label="수동 연결" className="space-y-3">
      <h3 className="font-semibold">수동 연결</h3>
      {displayError && <ErrorState error={displayError} />}
      {locked && (
        <div className="space-y-2 text-sm">
          <p>
            재전송을 막았습니다. 최신 문제와 업무 데이터의 연결, 실행 이력을
            확인한 뒤 문제 상세를 다시 열어 주세요.
          </p>
          <Button
            variant="secondary"
            disabled={unavailable}
            onClick={() =>
              void queryClient.invalidateQueries({
                queryKey: ['issues', 'detail', issue.id],
              })
            }
          >
            최신 문제 다시 조회
          </Button>
        </div>
      )}
      {mutation.isSuccess && (
        <div role="status" className="space-y-1 text-sm">
          <p>수동 연결을 저장했습니다. 조치완료로 바꾼 뒤 재검증하세요.</p>
          <p>
            연결 #{mutation.data.data.linkId} · 감사 실행 #
            {mutation.data.data.actionExecutionId}
          </p>
        </div>
      )}
      {!locked && !mutation.isSuccess && (
        <fieldset
          disabled={mutation.isPending || unavailable || !eligible}
          className="space-y-3"
        >
          {review && (
            <section
              aria-label="수동 연결 실행 전 확인"
              className="space-y-3 rounded-lg bg-slate-50 p-3 text-sm"
            >
              <h4 className="font-semibold">연결할 내용 확인</h4>
              <p>출발 객체: #{issue.objectInstanceId}</p>
              <p>
                도착 객체: {review.target.displayName} ·{' '}
                {review.target.objectTypeName} · #{review.target.id}
              </p>
              <p className="whitespace-pre-wrap break-words">
                연결 사유: {review.reason}
              </p>
              <p>저장 후 조치완료로 변경하고 재검증해야 해결할 수 있습니다.</p>
              {!currentReview && (
                <p role="alert">
                  문제가 변경됐습니다. 입력 내용을 다시 확인해 주세요.
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  onClick={save}
                  disabled={!canReview || !currentReview}
                  pending={mutation.isPending}
                >
                  수동 연결 저장
                </Button>
                <Button variant="secondary" onClick={() => setReview(null)}>
                  입력 수정
                </Button>
              </div>
            </section>
          )}
          <div hidden={review !== null} className="space-y-3">
            <IssueManualLinkTargetPicker
              sourceObjectId={issue.objectInstanceId!}
              value={target}
              onChange={(object) => {
                setTarget(object)
                mutation.reset()
              }}
            />
            <label className="flex flex-col gap-1 text-sm">
              연결 사유
              <textarea
                required
                maxLength={1000}
                className="rounded-lg border border-slate-300 p-2"
                value={reason}
                onChange={(event) => {
                  setReason(event.target.value)
                  mutation.reset()
                }}
              />
            </label>
            <Button
              disabled={!canReview}
              onClick={() => {
                if (canReview)
                  setReview({
                    target,
                    reason: reason.trim(),
                    rowVersion: issue.rowVersion,
                  })
              }}
            >
              연결 내용 확인
            </Button>
          </div>
        </fieldset>
      )}
    </section>
  )
}
