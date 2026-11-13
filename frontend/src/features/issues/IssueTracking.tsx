import { formatKstDateTime } from '../../utils/dateTime'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchIssueHistory, recordIssueForward, type Issue } from './api'

export default function IssueTracking({
  issue,
  statusLabels,
}: {
  issue: Issue
  statusLabels: Record<string, string>
}) {
  const forwardingHintId = useId()
  const client = useQueryClient()
  const submitting = useRef(false)
  const [page, setPage] = useState(0)
  const history = useQuery({
    queryKey: ['issues', 'history', issue.id, page],
    queryFn: ({ signal }) => fetchIssueHistory(issue.id, page, signal),
    retry: false,
  })
  const detailKey = ['issues', 'detail', issue.id]
  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: detailKey }),
      client.invalidateQueries({ queryKey: ['issues', 'list'] }),
      client.invalidateQueries({ queryKey: ['issues', 'history', issue.id] }),
    ])
  }
  const forwarding = useMutation({
    mutationFn: () => recordIssueForward(issue.id, issue.rowVersion),
    retry: false,
    onSuccess: async (response) => {
      await client.cancelQueries({ queryKey: detailKey })
      client.setQueryData<typeof response>(detailKey, (current) =>
        current && current.data.rowVersion > response.data.rowVersion
          ? current
          : response,
      )
      await refresh()
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.kind === 'conflict')
        await refresh()
    },
    onSettled: () => {
      submitting.current = false
    },
  })
  const canRecord =
    issue.status !== 'RESOLVED' &&
    Number.isSafeInteger(issue.rowVersion) &&
    issue.rowVersion >= 0
  const uncertain =
    forwarding.error &&
    (!(forwarding.error instanceof ApiError) ||
      ['network', 'server', 'invalid-response'].includes(forwarding.error.kind))

  function record() {
    if (!canRecord || submitting.current || forwarding.isPending) return
    submitting.current = true
    forwarding.mutate()
  }

  return (
    <>
      <section aria-label="원천 전달 기록" className="space-y-2">
        <h3 className="font-semibold">원천 전달 기록</h3>
        <dl className="grid gap-3 rounded-lg bg-slate-50 p-3 text-sm sm:grid-cols-3">
          <div>
            <dt>전달 여부</dt>
            <dd>{issue.forwardedAt ? '전달됨' : '미전달'}</dd>
          </div>
          <div>
            <dt>전달 시각</dt>
            <dd>
              {issue.forwardedAt ? formatKstDateTime(issue.forwardedAt) : '—'}
            </dd>
          </div>
          <div>
            <dt>전달 처리자</dt>
            <dd>
              {issue.forwardedBy == null ? '—' : `계정 #${issue.forwardedBy}`}
            </dd>
          </div>
        </dl>
        {issue.status === 'RESOLVED' ? (
          <p className="text-sm text-slate-500">
            해결된 문제는 전달 기록을 변경할 수 없습니다.
          </p>
        ) : (
          <>
            <Button
              variant="secondary"
              disabled={!canRecord || forwarding.isPending}
              aria-describedby={forwardingHintId}
              onClick={record}
            >
              {forwarding.isPending
                ? '기록 중…'
                : issue.forwardedAt
                  ? '다시 전달함으로 기록'
                  : '전달함으로 기록'}
            </Button>
            <p id={forwardingHintId} className="text-sm text-slate-500">
              원천 담당자에게 이미 전달한 사실을 기록합니다. 메시지를 보내거나
              처리 상태를 바꾸지 않습니다.
            </p>
            {issue.forwardedAt && (
              <p className="text-sm text-slate-500">
                다시 기록하면 마지막 전달 시각과 처리자가 갱신됩니다.
              </p>
            )}
          </>
        )}
        {forwarding.isSuccess && (
          <p role="status">전달한 사실을 기록했습니다.</p>
        )}
        {forwarding.error && <ErrorState error={forwarding.error} />}
        {uncertain && (
          <p>
            접수 여부가 불확실하므로 다시 기록하기 전에 최신 기록을 확인해
            주세요.
          </p>
        )}
        <Button
          variant="secondary"
          disabled={forwarding.isPending}
          onClick={() => void refresh()}
        >
          최신 기록 다시 불러오기
        </Button>
      </section>
      <section aria-label="상태 변경 이력" className="space-y-2">
        <h3 className="font-semibold">상태 변경 이력</h3>
        {history.isPending ? (
          <LoadingState message="상태 변경 이력을 불러오는 중…" />
        ) : history.isError ? (
          <ErrorState
            error={history.error}
            onRetry={() => void history.refetch()}
          />
        ) : (
          <>
            {history.data.data.length ? (
              <ul className="space-y-2 text-sm">
                {history.data.data.map((entry) => (
                  <li key={entry.id} className="rounded-lg bg-slate-50 p-3">
                    <p className="font-medium">
                      {entry.beforeStatus &&
                        `${statusLabels[entry.beforeStatus] ?? entry.beforeStatus} → `}
                      {statusLabels[entry.afterStatus] ?? entry.afterStatus}
                    </p>
                    <p>
                      {entry.changedBy != null
                        ? `계정 #${entry.changedBy}`
                        : '시스템'}{' '}
                      ·{' '}
                      <time dateTime={entry.changedAt}>
                        {formatKstDateTime(entry.changedAt)}
                      </time>
                    </p>
                    {entry.checkRunId != null && (
                      <p>근거 검사 #{entry.checkRunId}</p>
                    )}
                    {entry.note && (
                      <p className="whitespace-pre-wrap break-words">
                        {entry.note}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState message="상태 변경 이력이 없습니다." />
            )}
            {(history.data.meta.totalElements > history.data.meta.size ||
              page > 0) && (
              <Pagination {...history.data.meta} onChange={setPage} />
            )}
          </>
        )}
      </section>
    </>
  )
}
