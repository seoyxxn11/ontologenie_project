import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import { changeIssueStatus, type Issue } from './api'

const transitions: Record<string, readonly string[]> = {
  NEW: ['CHECKING'],
  CHECKING: ['FIXED_PENDING_REVERIFY'],
  FIXED_PENDING_REVERIFY: ['CHECKING', 'RESOLVED'],
}

export default function IssueStatusForm({
  issue,
  statusLabels,
}: {
  issue: Issue
  statusLabels: Record<string, string>
}) {
  const queryClient = useQueryClient()
  const submitting = useRef(false)
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [runId, setRunId] = useState('')
  const detailKey = ['issues', 'detail', issue.id]
  const listKey = ['issues', 'list']
  const mutation = useMutation({
    mutationFn: (request: Parameters<typeof changeIssueStatus>[1]) =>
      changeIssueStatus(issue.id, request),
    retry: false,
    onSuccess: async (response) => {
      await queryClient.cancelQueries({ queryKey: detailKey })
      queryClient.setQueryData<typeof response>(detailKey, (current) =>
        !response ||
        (current && current.data.rowVersion > response.data.rowVersion)
          ? current
          : response,
      )
      setTarget('')
      setReason('')
      setRunId('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: listKey }),
        queryClient.invalidateQueries({
          queryKey: ['issues', 'history', issue.id],
        }),
      ])
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.kind === 'conflict') {
        setTarget('')
        setRunId('')
        await queryClient.invalidateQueries({ queryKey: ['issues'] })
      }
    },
    onSettled: () => {
      submitting.current = false
    },
  })
  const options = transitions[issue.status] ?? []
  const resolving = target === 'RESOLVED'
  const runNumber = Number(runId)
  const canSubmit =
    options.includes(target) &&
    reason.trim().length > 0 &&
    Number.isSafeInteger(issue.rowVersion) &&
    (!resolving || (Number.isSafeInteger(runNumber) && runNumber > 0))

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canSubmit || submitting.current || mutation.isPending) return
    submitting.current = true
    mutation.mutate({
      status: target,
      reason: reason.trim(),
      rowVersion: issue.rowVersion,
      ...(resolving ? { resolvedCheckRunId: runNumber } : {}),
    })
  }

  return (
    <section aria-label="처리 상태 변경" className="space-y-2">
      {mutation.isSuccess && (
        <p role="status" className="ui-save-notice">
          처리 상태를 변경했습니다.
        </p>
      )}
      {mutation.error && <ErrorState error={mutation.error} />}
      {options.length > 0 && (
        <form onSubmit={submit}>
          <fieldset disabled={mutation.isPending} className="space-y-3">
            <legend className="font-semibold">처리 상태 변경</legend>
            <label className="flex flex-col gap-1 text-sm">
              변경할 상태
              <select
                required
                className="rounded-lg border border-slate-300 p-2"
                value={options.includes(target) ? target : ''}
                onChange={(event) => {
                  mutation.reset()
                  setTarget(event.target.value)
                  setRunId('')
                }}
              >
                <option value="">선택하세요</option>
                {options.map((status) => (
                  <option key={status} value={status}>
                    {statusLabels[status]}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              변경 사유
              <textarea
                required
                maxLength={1000}
                className="rounded-lg border border-slate-300 p-2"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </label>
            {resolving && options.includes(target) && (
              <label className="flex flex-col gap-1 text-sm">
                통과한 재검증 실행 ID
                <input
                  type="number"
                  min={1}
                  step={1}
                  required
                  className="rounded-lg border border-slate-300 p-2"
                  value={runId}
                  onChange={(event) => setRunId(event.target.value)}
                />
              </label>
            )}
            <Button type="submit" disabled={!canSubmit || mutation.isPending}>
              {mutation.isPending ? '변경 중…' : '상태 변경'}
            </Button>
          </fieldset>
        </form>
      )}
    </section>
  )
}
