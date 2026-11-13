import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import {
  useLocation,
  useNavigate,
  useSearchParams,
  type NavigateOptions,
} from 'react-router'
import { ApiError } from '../../api/errors'
import DataTable from '../../components/DataTable'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import IssueManualLinkForm from './IssueManualLinkForm'
import IssueReverification from './IssueReverification'
import IssueStatusForm from './IssueStatusForm'
import IssueTracking from './IssueTracking'
import { fetchIssue, fetchIssues, type Issue } from './api'

const statusLabels: Record<string, string> = {
  NEW: '신규',
  CHECKING: '확인중',
  FIXED_PENDING_REVERIFY: '조치완료·재검증대기',
  RESOLVED: '해결',
}

const kindLabels: Record<string, string> = {
  UNMATCHED: '미연결',
  DUPLICATE: '중복',
  VIOLATION: '위반',
}

function relatedRule(issue: Issue) {
  if (issue.linkMappingId !== null) {
    const code = /^[^:]+:rule:(.+):object:\d+$/.exec(issue.dedupeKey)?.[1]
    return code
      ? `${code} (연결 매핑 #${issue.linkMappingId})`
      : `연결 매핑 #${issue.linkMappingId}`
  }
  return issue.proposalItemId === null ? '—' : `제안 #${issue.proposalItemId}`
}

function IssueDetail({ id, onClose }: { id: number; onClose: () => void }) {
  const [savingLink, setSavingLink] = useState(false)
  const query = useQuery({
    queryKey: ['issues', 'detail', id],
    queryFn: ({ signal }) => fetchIssue(id, signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const issue = query.data?.data
  const hiddenDetail =
    query.error instanceof ApiError &&
    ['unauthenticated', 'forbidden', 'not-found'].includes(query.error.kind)
  const evidence = Object.entries(issue?.evidence ?? {}).filter(
    ([key]) => key !== 'causeConfirmed',
  )

  return (
    <Dialog
      title={`적용 후 문제 상세 #${id}`}
      onClose={() => {
        if (!savingLink) onClose()
      }}
      closeOnBackdrop={!savingLink}
    >
      <fieldset disabled={savingLink} className="mt-4 min-w-0 space-y-4">
        {query.isPending && <LoadingState message="문제 상세를 불러오는 중…" />}
        {query.isError && (
          <ErrorState
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        )}
        {issue && !hiddenDetail && (
          <>
            <dl className="grid gap-3 text-sm sm:grid-cols-2">
              {[
                ['문제 유형', kindLabels[issue.issueKind] ?? issue.issueKind],
                ['처리 상태', statusLabels[issue.status] ?? issue.status],
                ['관련 규칙', relatedRule(issue)],
                [
                  '관련 객체',
                  issue.objectInstanceId === null
                    ? null
                    : `#${issue.objectInstanceId}`,
                ],
                ['원본 값', issue.rawValue],
                ['변환 결과', issue.transformedValue],
                ['실패 이유', issue.failureReason],
                [
                  '원인 확인',
                  issue.evidence.causeConfirmed === true
                    ? '원인 확인됨'
                    : '원인 미확정',
                ],
              ].map(([label, value]) => (
                <div key={label} className="min-w-0 rounded-lg bg-slate-50 p-3">
                  <dt className="font-medium text-slate-600">{label}</dt>
                  <dd className="mt-1 whitespace-pre-wrap break-words">
                    {value ?? '—'}
                  </dd>
                </div>
              ))}
            </dl>
            {issue.issueKind === 'UNMATCHED' &&
              issue.status === 'CHECKING' &&
              issue.objectInstanceId !== null &&
              issue.linkMappingId !== null && (
                <IssueManualLinkForm
                  issue={issue}
                  unavailable={query.isFetching || query.isError}
                  onPendingChange={setSavingLink}
                />
              )}
            <fieldset
              disabled={query.isFetching || query.isError}
              className="min-w-0 space-y-4"
            >
              <IssueStatusForm issue={issue} statusLabels={statusLabels} />
              <IssueReverification
                key={`reverification:${issue.id}`}
                issue={issue}
              />
              <IssueTracking
                key={issue.id}
                issue={issue}
                statusLabels={statusLabels}
              />
            </fieldset>
            <section aria-label="원천 근거" className="space-y-2">
              <h3 className="font-semibold">원천 근거</h3>
              {evidence.length ? (
                <dl className="space-y-2 text-sm">
                  {evidence.map(([key, value]) => (
                    <div key={key} className="rounded-lg bg-slate-50 p-3">
                      <dt className="break-words font-medium">{key}</dt>
                      <dd className="mt-1 whitespace-pre-wrap break-words">
                        {typeof value === 'string'
                          ? value
                          : JSON.stringify(value, null, 2)}
                      </dd>
                    </div>
                  ))}
                </dl>
              ) : (
                <p className="text-sm text-slate-500">
                  추가 원천 근거가 없습니다.
                </p>
              )}
            </section>
          </>
        )}
      </fieldset>
    </Dialog>
  )
}

export default function IssuesPanel() {
  const location = useLocation()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const issueKind = params.get('issueKind') ?? ''
  const status = params.get('issueStatus') ?? ''
  const filters = {
    issueKind: Object.hasOwn(kindLabels, issueKind) ? issueKind : '',
    status: Object.hasOwn(statusLabels, status) ? status : '',
  }
  const pageValue = Number(params.get('issuePage') ?? 0)
  const page = Number.isSafeInteger(pageValue) && pageValue >= 0 ? pageValue : 0
  const issueId = Number(params.get('issue'))
  const query = useQuery({
    queryKey: ['issues', 'list', filters, page],
    queryFn: ({ signal }) => fetchIssues(filters, page, signal),
    retry: false,
  })

  function update(values: Record<string, string>, options?: NavigateOptions) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next, options)
  }

  function closeIssue() {
    if (location.state?.issueOpenedFromList === true) {
      void navigate(-1)
    } else {
      update({ issue: '' }, { replace: true })
    }
  }

  const meta = query.data?.meta
  return (
    <section className="rules-issues space-y-4 rounded-xl border border-slate-200 p-5">
      <h2 className="text-base font-semibold">적용 후 문제</h2>
      <div
        className="issue-status-tabs"
        role="group"
        aria-label="문제 상태 필터"
      >
        {Object.entries({ '': '전체', ...statusLabels }).map(
          ([value, label]) => (
            <button
              type="button"
              key={value}
              aria-pressed={filters.status === value}
              onClick={() =>
                update({ issueStatus: value, issuePage: '', issue: '' })
              }
            >
              {label}
            </button>
          ),
        )}
      </div>
      <details className="rules-extra-filters">
        <summary>문제 상세 필터</summary>
        <div className="flex flex-wrap gap-4">
          {[
            {
              key: 'issueStatus',
              label: '처리 상태',
              value: filters.status,
              options: statusLabels,
            },
            {
              key: 'issueKind',
              label: '문제 유형',
              value: filters.issueKind,
              options: kindLabels,
            },
          ].map(({ key, label, value, options }) => (
            <label key={key} className="flex flex-col gap-2 text-sm">
              {label}
              <select
                className="rounded-lg border border-slate-300 px-3 py-2"
                value={value}
                onChange={(event) =>
                  update({
                    [key]: event.target.value,
                    issuePage: '',
                    issue: '',
                  })
                }
              >
                <option value="">전체</option>
                {Object.entries(options).map(([option, name]) => (
                  <option key={option} value={option}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
          ))}
        </div>
      </details>
      {query.isPending && <LoadingState message="문제 목록을 불러오는 중…" />}
      {query.isError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}
      {query.isSuccess && (
        <>
          {query.data.data.length ? (
            <DataTable label="정합성 문제 목록" className="min-w-[640px]">
              <thead className="bg-slate-50 text-left text-xs text-slate-600">
                <tr>
                  {['문제·행', '관련 규칙', '상태', '실패 이유'].map(
                    (label) => (
                      <th key={label} scope="col" className="px-4 py-3">
                        {label}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-sm">
                {query.data.data.map((issue) => (
                  <tr key={issue.id}>
                    <td className="px-4 py-3">
                      <button
                        type="button"
                        className="text-violet-700 underline"
                        onClick={() =>
                          update(
                            { issue: String(issue.id) },
                            { state: { issueOpenedFromList: true } },
                          )
                        }
                      >
                        #{issue.id} ·{' '}
                        {kindLabels[issue.issueKind] ?? issue.issueKind}
                      </button>
                    </td>
                    <td className="px-4 py-3">{relatedRule(issue)}</td>
                    <td className="px-4 py-3">
                      <StatusBadge>
                        {statusLabels[issue.status] ?? issue.status}
                      </StatusBadge>
                    </td>
                    <td className="px-4 py-3">{issue.failureReason ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </DataTable>
          ) : (
            <EmptyState message="조건에 맞는 정합성 문제가 없습니다." />
          )}
          {meta && (meta.totalElements > 0 || page > 0) && (
            <Pagination
              page={page}
              size={meta.size}
              totalElements={meta.totalElements}
              onChange={(next) =>
                update({ issuePage: next ? String(next) : '', issue: '' })
              }
            />
          )}
        </>
      )}
      {Number.isSafeInteger(issueId) && issueId > 0 && (
        <IssueDetail key={issueId} id={issueId} onClose={closeIssue} />
      )}
    </section>
  )
}
