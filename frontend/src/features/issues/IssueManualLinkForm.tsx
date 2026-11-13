import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import { createIssueManualLink, type Issue } from './api'

export default function IssueManualLinkForm({ issue }: { issue: Issue }) {
  const queryClient = useQueryClient()
  const submitting = useRef(false)
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const mutation = useMutation({
    mutationFn: createIssueManualLink,
    retry: false,
    onSuccess: () => {
      setTarget('')
      setReason('')
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.kind === 'conflict') {
        await queryClient.invalidateQueries({
          queryKey: ['issues', 'detail', issue.id],
        })
      }
    },
    onSettled: () => {
      submitting.current = false
    },
  })
  const targetObjectId = Number(target)
  const canSubmit =
    /^\d+$/.test(target) &&
    Number.isSafeInteger(targetObjectId) &&
    targetObjectId > 0 &&
    reason.trim().length > 0 &&
    reason.length <= 1000

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canSubmit || submitting.current || mutation.isPending) return
    submitting.current = true
    mutation.mutate({
      issueId: issue.id,
      targetObjectId,
      reason: reason.trim(),
      rowVersion: issue.rowVersion,
    })
  }

  return (
    <section aria-label="수동 연결" className="space-y-2">
      {mutation.isSuccess && (
        <p role="status">
          수동 연결을 저장했습니다. 조치완료로 바꾼 뒤 재검증하세요.
        </p>
      )}
      {mutation.error && <ErrorState error={mutation.error} />}
      <form onSubmit={submit}>
        <fieldset disabled={mutation.isPending} className="space-y-3">
          <legend className="font-semibold">수동 연결</legend>
          <label className="flex flex-col gap-1 text-sm">
            연결할 도착 객체 ID
            <input
              inputMode="numeric"
              required
              className="rounded-lg border border-slate-300 p-2"
              value={target}
              onChange={(event) => setTarget(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            연결 사유
            <textarea
              required
              maxLength={1000}
              className="rounded-lg border border-slate-300 p-2"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <Button type="submit" disabled={!canSubmit || mutation.isPending}>
            {mutation.isPending ? '저장 중…' : '수동 연결 저장'}
          </Button>
        </fieldset>
      </form>
    </section>
  )
}
