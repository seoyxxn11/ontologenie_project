import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import AnalysisPanel from '../analysis/AnalysisPanel'
import { fetchAnalysisProposals } from '../analysis/api'
import { fetchProposals, PROPOSAL_PAGE_SIZE } from './api'
import {
  applyLabels,
  confidence,
  kindLabels,
  originLabels,
  proposalTitle,
  reviewLabels,
} from './labels'
import type { ProposalFilters } from './types'

const fields = [
  { key: 'origin', label: '제안 출처', options: originLabels },
  { key: 'itemKind', label: '제안 종류', options: kindLabels },
  { key: 'reviewStatus', label: '검토 상태', options: reviewLabels },
  { key: 'applyStatus', label: '반영 상태', options: applyLabels },
] as const

function ProposalFilterForm({
  initialFilters,
  pending,
  onSearch,
}: {
  initialFilters: ProposalFilters
  pending: boolean
  onSearch: (filters: ProposalFilters) => void
}) {
  const [draft, setDraft] = useState(initialFilters)
  return (
    <form
      className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-[repeat(4,1fr)_auto_auto]"
      onSubmit={(event) => {
        event.preventDefault()
        onSearch(draft)
      }}
    >
      {fields.map((field) => (
        <label key={field.key} className="flex flex-col gap-2 text-sm">
          {field.label}
          <select
            className="rounded-lg border border-slate-300 px-3 py-2"
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

export default function ProposalsPage() {
  const [params, setParams] = useSearchParams()
  const filters: ProposalFilters = {}
  for (const field of fields) {
    const value = params.get(field.key)
    if (value && Object.hasOwn(field.options, value)) filters[field.key] = value
  }
  const pageValue = Number(params.get('page') ?? 0)
  const page = Number.isSafeInteger(pageValue) && pageValue >= 0 ? pageValue : 0
  // 분석을 고르면 그 분석이 만든 제안만 본다(020, S15P31C106-108).
  const analysisValue = Number(params.get('analysis'))
  const analysisId =
    Number.isSafeInteger(analysisValue) && analysisValue > 0
      ? analysisValue
      : undefined
  const query = useQuery({
    queryKey: ['proposals', 'list', filters, page, analysisId],
    queryFn: ({ signal }) =>
      analysisId === undefined
        ? fetchProposals(filters, page, signal)
        : fetchAnalysisProposals(analysisId, filters, page, signal),
    retry: false,
  })
  function update(
    next: ProposalFilters,
    nextPage = 0,
    nextAnalysis: { id?: number; joined?: boolean } = { id: analysisId },
  ) {
    const search = new URLSearchParams()
    if (nextAnalysis.id !== undefined) {
      search.set('analysis', String(nextAnalysis.id))
      if (nextAnalysis.joined) search.set('joined', '1')
    }
    for (const [key, value] of Object.entries(next))
      if (value) search.set(key, value)
    if (nextPage) search.set('page', String(nextPage))
    setParams(search)
  }
  let content
  if (query.isPending)
    content = <LoadingState message="제안 목록을 불러오는 중…" />
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
    content = <EmptyState message="조건에 맞는 제안이 없습니다." />
  else
    content = (
      <>
        <DataTable label="검토 제안 목록" className="min-w-[760px]">
          <thead className="bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              {['제안', '종류·출처', '검토 상태', '반영 상태', 'AI 신뢰도'].map(
                (title) => (
                  <th key={title} className="px-4 py-3 font-medium">
                    {title}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {query.data.data.map((item) => (
              <tr key={item.id}>
                <td className="px-4 py-4">
                  <Link
                    to={`${item.id}?${params}`}
                    className="font-medium text-violet-700 underline underline-offset-4"
                  >
                    {proposalTitle(item)}
                  </Link>
                  <p className="mt-1 text-xs text-slate-500">
                    #{item.id} · 개정 {item.revision}
                  </p>
                </td>
                <td className="px-4 py-4 text-sm">
                  {kindLabels[item.itemKind] ?? item.itemKind}
                  <p className="text-xs text-slate-500">
                    {originLabels[item.origin] ?? item.origin}
                  </p>
                </td>
                <td className="px-4 py-4">
                  <StatusBadge>
                    {reviewLabels[item.reviewStatus] ?? item.reviewStatus}
                  </StatusBadge>
                </td>
                <td className="px-4 py-4">
                  <StatusBadge
                    tone={item.applyStatus === 'FAILED' ? 'danger' : 'neutral'}
                  >
                    {applyLabels[item.applyStatus] ?? item.applyStatus}
                  </StatusBadge>
                </td>
                <td className="px-4 py-4 text-sm">
                  {confidence(item.aiConfidence)}
                </td>
              </tr>
            ))}
          </tbody>
        </DataTable>
        {query.data.meta && (
          <Pagination
            page={page}
            size={PROPOSAL_PAGE_SIZE}
            totalElements={query.data.meta.totalElements}
            onChange={(next) => update(filters, next)}
          />
        )}
      </>
    )
  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">연결 규칙</h1>
        <p className="mt-2 text-sm text-slate-500">
          분석 결과와 직접 추가한 제안을 함께 검토하고, 제안 내용과 근거를
          확인하세요.
        </p>
      </header>
      <AnalysisPanel
        currentId={analysisId}
        joined={params.get('joined') === '1'}
        onSelect={(id) => update(filters, 0, { id })}
        onStarted={(run) => update({}, 0, { id: run.id, joined: run.joined })}
      />
      <section className="space-y-5 rounded-xl border border-slate-200 p-5">
        <h2 className="text-lg font-semibold">검토 제안</h2>
        <Link
          to="/admin/rules/new"
          className="inline-block rounded-lg bg-violet-700 px-4 py-2 text-sm font-medium text-white"
        >
          규칙 후보 추가
        </Link>
        <ProposalFilterForm
          key={params.toString()}
          initialFilters={filters}
          pending={query.isFetching}
          onSearch={(next) => update(next)}
        />
        {query.isSuccess && (
          <p className="text-sm text-slate-500">
            {query.data.meta?.totalElements ?? query.data.data.length}건
          </p>
        )}
        {content}
      </section>
    </div>
  )
}
