import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import { agentUsage } from './agentUsage'
import {
  ACTION_PAGE_SIZE,
  actionPath,
  fetchActionDefinitions,
  type ActionFilters,
} from './api'
import './ActionDefinitionsPage.css'
import { activeLabels, changesDataLabels } from './labels'

function Filters({
  initial,
  pending,
  onSearch,
}: {
  initial: ActionFilters
  pending: boolean
  onSearch: (filters: ActionFilters) => void
}) {
  const [draft, setDraft] = useState(initial)
  return (
    <form
      className="management-filter actions-filter"
      onSubmit={(event) => {
        event.preventDefault()
        onSearch(draft)
      }}
    >
      <div className="actions-filter-group">
        <label className="flex flex-col gap-2 text-sm">
          사용 여부
          <select
            className="rounded-lg border p-2"
            value={draft.isActive ?? ''}
            onChange={(event) =>
              setDraft({
                ...draft,
                isActive:
                  (event.target.value as ActionFilters['isActive']) ||
                  undefined,
              })
            }
          >
            <option value="">전체</option>
            {Object.entries(activeLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-2 text-sm">
          데이터 변경
          <select
            className="rounded-lg border p-2"
            value={draft.actionKind ?? ''}
            onChange={(event) =>
              setDraft({
                ...draft,
                actionKind:
                  (event.target.value as ActionFilters['actionKind']) ||
                  undefined,
              })
            }
          >
            <option value="">전체</option>
            {Object.entries(changesDataLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <Button type="submit" variant="secondary" pending={pending}>
          검색
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            setDraft({})
            onSearch({})
          }}
        >
          초기화
        </Button>
      </div>
      <Link className="ui-button ui-button--primary" to="/admin/actions/new">
        Action 등록
      </Link>
    </form>
  )
}

function readFilters(params: URLSearchParams): ActionFilters {
  const filters: ActionFilters = {}
  const active = params.get('isActive')
  const kind = params.get('actionKind')
  if (active === 'true' || active === 'false') filters.isActive = active
  if (kind === 'READ' || kind === 'WRITE') filters.actionKind = kind
  return filters
}

export default function ActionDefinitionsPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const query = useQuery({
    queryKey: ['actions', 'list', filters, page],
    queryFn: ({ signal }) => fetchActionDefinitions(filters, page, signal),
    retry: false,
  })
  function search(next: ActionFilters, nextPage = 0) {
    const value = new URLSearchParams()
    for (const [key, entry] of Object.entries(next))
      if (entry) value.set(key, entry)
    if (nextPage) value.set('page', String(nextPage))
    setParams(value)
  }
  let content
  if (query.isPending)
    content = <LoadingState message="Action 정의를 불러오는 중…" />
  else if (query.isError)
    content = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else if (!query.data.data.length)
    content = <EmptyState message="조건에 맞는 Action 정의가 없습니다." />
  else
    content = (
      <>
        <DataTable label="Action 정의 목록" className="min-w-[760px]">
          <thead className="bg-slate-50 text-left text-sm text-slate-500">
            <tr>
              {['이름·코드', '데이터 변경', '사용', 'Agent 사용'].map(
                (label) => (
                  <th key={label} className="px-4 py-3">
                    {label}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {query.data.data.map((item) => {
              const usage = agentUsage(item)
              return (
                <tr key={item.id}>
                  <td className="px-4 py-3">
                    <Link
                      className="font-medium text-violet-700 underline"
                      to={
                        params.size
                          ? `${actionPath(item.code)}?${params}`
                          : actionPath(item.code)
                      }
                    >
                      {item.name}
                    </Link>
                    <p className="mt-1 break-all font-mono text-sm text-slate-500">
                      {item.code}
                    </p>
                  </td>
                  <td className="px-4 py-3">
                    {changesDataLabels[item.actionKind] ?? item.actionKind}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge tone={item.isActive ? 'success' : 'neutral'}>
                      {item.isActive ? activeLabels.true : activeLabels.false}
                    </StatusBadge>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <StatusBadge tone={usage.usable ? 'success' : 'warning'}>
                      {usage.label}
                    </StatusBadge>
                    {!usage.usable && (
                      <p className="mt-1 text-sm text-slate-500">
                        {usage.reason}
                      </p>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </DataTable>
        {query.data.meta && (
          <Pagination
            page={page}
            size={ACTION_PAGE_SIZE}
            totalElements={query.data.meta.totalElements}
            onChange={(next) => search(filters, next)}
          />
        )}
      </>
    )
  return (
    <div className="management-page space-y-6">
      <PageHeader
        title="Action 정의"
        description="업무에 사용하는 Action의 변경 여부와 사용 상태를 확인합니다."
      />
      <Filters
        key={params.toString()}
        initial={filters}
        pending={query.isFetching}
        onSearch={(next) => search(next)}
      />
      {query.isSuccess && (
        <p className="text-sm text-slate-500">
          {query.data.meta?.totalElements ?? query.data.data.length}건
        </p>
      )}
      {content}
    </div>
  )
}
