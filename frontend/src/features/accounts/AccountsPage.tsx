import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
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
      className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_auto_auto]"
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
      <Button type="submit" pending={pending}>
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
      <>
        <DataTable label="계정 목록" className="min-w-[680px]">
          <thead className="bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              {['로그인 ID', '이름', '역할', '상태', '비밀번호 변경'].map(
                (label) => (
                  <th key={label} className="px-4 py-3">
                    {label}
                  </th>
                ),
              )}
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
                <td className="px-4 py-3 text-sm">
                  {item.mustChangePassword
                    ? '첫 로그인 시 변경 필요'
                    : '설정 완료'}
                </td>
              </tr>
            ))}
          </tbody>
        </DataTable>
        {query.data.meta && (
          <Pagination
            page={page}
            size={ACCOUNT_PAGE_SIZE}
            totalElements={query.data.meta.totalElements}
            onChange={(next) => search(filters, next)}
          />
        )}
      </>
    )
  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">계정·권한</h1>
          <p className="mt-2 text-sm text-slate-500">
            계정을 발급하고 역할·로그인 상태를 관리합니다.
          </p>
        </div>
        <Link
          className="rounded-lg bg-violet-700 px-4 py-2 text-sm text-white"
          to="/admin/accounts/new"
        >
          계정 생성
        </Link>
      </header>
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
