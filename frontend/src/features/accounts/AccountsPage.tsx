import './accounts.css'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import { roleLabels } from '../auth/access'
import {
  ACCOUNT_PAGE_SIZE,
  fetchAccounts,
  statusLabels,
  type AccountFilters,
} from './api'

function Filters({
  initial,
  pending,
  onSearch,
}: {
  initial: AccountFilters
  pending: boolean
  onSearch: (filters: AccountFilters) => void
}) {
  const [draft, setDraft] = useState(initial)
  return (
    <form
      className="management-filter accounts-filter"
      onSubmit={(event) => {
        event.preventDefault()
        onSearch(draft)
      }}
    >
      <label className="flex flex-col gap-2 text-sm">
        로그인 ID·이름 검색
        <input
          className="rounded-lg border p-2"
          value={draft.q ?? ''}
          onChange={(event) => setDraft({ ...draft, q: event.target.value })}
        />
      </label>
      {(
        [
          { key: 'roleCode', label: '역할', options: roleLabels },
          { key: 'status', label: '계정 상태', options: statusLabels },
        ] as const
      ).map((field) => (
        <label key={field.key} className="flex flex-col gap-2 text-sm">
          {field.label}
          <select
            className="rounded-lg border p-2"
            value={draft[field.key] ?? ''}
            onChange={(event) =>
              setDraft({ ...draft, [field.key]: event.target.value })
            }
          >
            <option value="">전체</option>
            {Object.entries(field.options).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      ))}
      <Button variant="secondary" type="submit" pending={pending}>
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
      <Link
        className="ui-button ui-button--primary accounts-create"
        to="/admin/accounts/new"
      >
        계정 생성
      </Link>
    </form>
  )
}

export default function AccountsPage() {
  const [params, setParams] = useSearchParams()
  const filters: AccountFilters = { q: params.get('q') ?? '' }
  const status = params.get('status') ?? ''
  const role = params.get('roleCode') ?? ''
  if (Object.hasOwn(statusLabels, status)) filters.status = status
  if (Object.hasOwn(roleLabels, role)) filters.roleCode = role
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const query = useQuery({
    queryKey: ['accounts', 'list', filters, page],
    queryFn: ({ signal }) => fetchAccounts(filters, page, signal),
    retry: false,
  })
  function search(next: AccountFilters, nextPage = 0) {
    const value = new URLSearchParams()
    for (const [key, entry] of Object.entries(next))
      if (entry?.trim()) value.set(key, entry.trim())
    if (nextPage) value.set('page', String(nextPage))
    setParams(value)
  }
  let content
  if (query.isPending)
    content = <LoadingState message="계정 목록을 불러오는 중…" />
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
    content = <EmptyState message="조건에 맞는 계정이 없습니다." />
  else
    content = (
      <DataTable label="계정 목록" className="accounts-table">
        <colgroup>
          <col style={{ width: '28%' }} />
          <col style={{ width: '25%' }} />
          <col style={{ width: '28%' }} />
          <col style={{ width: '19%' }} />
        </colgroup>
        <thead className="bg-slate-50 text-left text-sm text-slate-500">
          <tr>
            {['로그인 ID', '이름', '역할', '상태'].map((label) => (
              <th key={label} className="px-4 py-3">
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {query.data.data.map((item) => (
            <tr key={item.id}>
              <td className="px-4 py-3">
                <Link
                  className="text-violet-700 underline"
                  to={`${item.id}?${params}`}
                >
                  {item.loginId}
                </Link>
              </td>
              <td className="px-4 py-3">{item.displayName}</td>
              <td className="px-4 py-3">
                {roleLabels[item.roleCode] ?? item.roleCode}
              </td>
              <td className="px-4 py-3">
                <StatusBadge
                  tone={item.status === 'ACTIVE' ? 'success' : 'warning'}
                >
                  {statusLabels[item.status] ?? item.status}
                </StatusBadge>
              </td>
            </tr>
          ))}
        </tbody>
      </DataTable>
    )
  return (
    <div className="management-page accounts-page">
      <PageHeader
        title="계정·권한"
        description="계정의 역할, 사용 상태와 비밀번호 변경 필요 여부를 확인합니다."
      />
      <Filters
        key={params.toString()}
        initial={filters}
        pending={query.isFetching}
        onSearch={(next) => search(next)}
      />
      <div className="accounts-results">{content}</div>
      {query.isSuccess && query.data.meta && (
        <Pagination
          page={page}
          size={ACCOUNT_PAGE_SIZE}
          totalElements={query.data.meta.totalElements}
          pending={query.isFetching}
          onChange={(next) => search(filters, next)}
        />
      )}
    </div>
  )
}
