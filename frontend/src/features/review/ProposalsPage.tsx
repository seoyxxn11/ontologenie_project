import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import AnalysisPanel from '../analysis/AnalysisPanel'
import { fetchAnalysisProposals } from '../analysis/api'
import IssuesPanel from '../issues/IssuesPanel'
import { fetchIssues } from '../issues/api'
import { fetchProposals, PROPOSAL_PAGE_SIZE } from './api'
import { collectProposals, isObjectProposal, matchesProposal } from './catalog'
import ProposalDetail from './ProposalDetail'
import {
  applyLabels,
  changeLabels,
  filterKindLabels,
  originLabels,
  proposalTitle,
  reviewLabels,
} from './labels'
import type { Proposal, ProposalFilters } from './types'

const fields = [
  { key: 'origin', label: '제안 출처', options: originLabels },
  { key: 'itemKind', label: '제안 종류', options: filterKindLabels },
  { key: 'reviewStatus', label: '검토 상태', options: reviewLabels },
  { key: 'applyStatus', label: '반영 상태', options: applyLabels },
] as const
const statuses = [
  ['', '전체'],
  ['PENDING', '검토 대기'],
  ['HELD', '보류'],
  ['APPROVED', '승인'],
  ['REJECTED', '거절'],
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
      onSubmit={(e) => {
        e.preventDefault()
        onSearch(draft)
      }}
    >
      {fields.map((field) => (
        <label key={field.key} className="flex flex-col gap-2 text-sm">
          {field.label}
          <select
            value={draft[field.key] ?? ''}
            onChange={(e) =>
              setDraft({ ...draft, [field.key]: e.target.value })
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

function ProposalFlags({ item }: { item: Proposal }) {
  const change = item.evidence?.changeType
  return (
    <div className="mt-1 flex flex-wrap gap-1">
      {item.evidence?.holdRecommended === true &&
        item.reviewStatus === 'PENDING' && (
          <StatusBadge tone="warning">보류 권고</StatusBadge>
        )}
      {typeof change === 'string' && changeLabels[change] && (
        <StatusBadge tone="info">재분석 {changeLabels[change]}</StatusBadge>
      )}
    </div>
  )
}

function ProposalSearch({
  search,
  onSearch,
}: {
  search: string
  onSearch: (value: string) => void
}) {
  const [draft, setDraft] = useState(search)
  return (
    <form
      className="rules-search"
      aria-label="후보 검색 적용"
      onSubmit={(event) => {
        event.preventDefault()
        onSearch(draft.trim())
      }}
    >
      <label>
        후보 검색
        <input
          type="search"
          value={draft}
          placeholder="후보명·원천·테이블 검색"
          onChange={(event) => setDraft(event.target.value)}
        />
      </label>
      <Button type="submit" variant="secondary">
        검색 적용
      </Button>
      <Button
        variant="text"
        onClick={() => {
          setDraft('')
          onSearch('')
        }}
      >
        검색어 지우기
      </Button>
    </form>
  )
}

export default function ProposalsPage() {
  const [params, setParams] = useSearchParams()
  const showingIssues = params.get('view') === 'issues'
  const analysisValue = Number(params.get('analysis'))
  const analysisId =
    Number.isSafeInteger(analysisValue) && analysisValue > 0
      ? analysisValue
      : undefined
  const query = useQuery({
    queryKey: ['proposals', 'catalog', analysisId],
    queryFn: ({ signal }) =>
      collectProposals((page) =>
        analysisId === undefined
          ? fetchProposals({}, page, signal)
          : fetchAnalysisProposals(analysisId, {}, page, signal),
      ),
    retry: false,
  })
  const issues = useQuery({
    queryKey: ['issues', 'count'],
    queryFn: ({ signal }) =>
      fetchIssues({ status: '', issueKind: '' }, 0, signal),
    retry: false,
  })
  const filters: ProposalFilters = {}
  for (const field of fields) {
    const value = params.get(field.key)
    if (value && Object.hasOwn(field.options, value)) filters[field.key] = value
  }
  function update(values: Record<string, string | undefined>, replace = false) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next, { replace })
  }
  const all = query.data ?? []
  const statusItems = all.filter(
    (item) =>
      !filters.reviewStatus || item.reviewStatus === filters.reviewStatus,
  )
  const search = params.get('q') ?? ''
  // 유형 탭 개수와 아래 목록이 같은 조건(출처·반영 상태·검색)을 쓴다. 유형 조건만 빼고 센다.
  const typeItems = statusItems.filter(
    (item) =>
      (!filters.origin || item.origin === filters.origin) &&
      (!filters.applyStatus || item.applyStatus === filters.applyStatus) &&
      matchesProposal(item, search),
  )
  const objectCount = typeItems.filter(isObjectProposal).length
  const mappingCount = typeItems.length - objectCount
  // 기본 유형은 고른 검토 상태 안에 객체 후보가 있는지로 정한다(전체 기준이면 빈 목록이 열렸다).
  const group =
    params.get('group') ??
    (statusItems.some(isObjectProposal) ? 'objects' : 'mappings')
  const filtered = typeItems.filter((item) =>
    !filters.itemKind
      ? isObjectProposal(item) === (group === 'objects')
      : item.itemKind === filters.itemKind,
  )
  const pageValue = Number(params.get('page') ?? 0)
  const requestedPage =
    Number.isSafeInteger(pageValue) && pageValue >= 0 ? pageValue : 0
  const page = Math.min(
    requestedPage,
    Math.max(0, Math.ceil(filtered.length / PROPOSAL_PAGE_SIZE) - 1),
  )
  const visible = filtered.slice(
    page * PROPOSAL_PAGE_SIZE,
    (page + 1) * PROPOSAL_PAGE_SIZE,
  )
  const selected =
    visible.find((item) => String(item.id) === params.get('candidate')) ??
    visible[0]
  const issueCount = issues.data?.meta?.totalElements
  const issueCountLabel = issues.isError
    ? '전체 건수 조회 실패'
    : issues.isPending
      ? '전체 건수 조회 중…'
      : typeof issueCount === 'number' &&
          Number.isSafeInteger(issueCount) &&
          issueCount >= 0
        ? `전체 ${issueCount}건 (해결 포함)`
        : '전체 건수 확인 불가'
  function selectAnalysis(id?: number, joined?: boolean) {
    update({
      analysis: id?.toString(),
      joined: joined ? '1' : '',
      page: '',
      candidate: '',
      q: '',
      reviewStatus: '',
      itemKind: '',
      group: '',
    })
  }
  return (
    <div className="rules-workspace">
      <h1 className="sr-only">연결 규칙</h1>
      <div className="rules-top-controls">
        <div className="rules-analysis-area">
          <AnalysisPanel
            compact
            currentId={analysisId}
            joined={params.get('joined') === '1'}
            onSelect={(id) => selectAnalysis(id)}
            onStarted={(run) => {
              const next = new URLSearchParams({ analysis: String(run.id) })
              if (run.joined) next.set('joined', '1')
              setParams(next)
            }}
          />
        </div>
        <div className="rules-page-actions">
          <Link className="ui-button ui-button--secondary" to="/admin/checks">
            수동 검사·실행 목록
          </Link>
          <Link
            className="ui-button ui-button--secondary"
            to={`/admin/rules/new?${params}`}
          >
            직접 관계 제안
          </Link>
        </div>
      </div>
      <nav className="rules-tabs" aria-label="연결 규칙 상태">
        {statuses.map(([value, label]) => (
          <button
            key={value}
            type="button"
            aria-pressed={
              !showingIssues && (filters.reviewStatus ?? '') === value
            }
            onClick={() =>
              update({
                view: '',
                reviewStatus: value,
                page: '',
                candidate: '',
                issue: '',
              })
            }
          >
            {label}{' '}
            {query.isSuccess
              ? value
                ? all.filter((item) => item.reviewStatus === value).length
                : all.length
              : '…'}
          </button>
        ))}
        <Link
          className="rules-issues-tab"
          aria-label="적용 후 문제"
          aria-current={showingIssues ? 'page' : undefined}
          to={{
            search: (() => {
              const p = new URLSearchParams(params)
              p.set('view', 'issues')
              return p.toString()
            })(),
          }}
        >
          적용 후 문제 · {issueCountLabel}
        </Link>
      </nav>
      {showingIssues ? (
        <IssuesPanel />
      ) : (
        <>
          <div className="rules-filter-toolbar">
            <div
              className="rules-kind-tabs"
              role="group"
              aria-label="후보 유형 필터"
            >
              <button
                type="button"
                aria-pressed={group === 'objects' && !filters.itemKind}
                onClick={() =>
                  update({
                    group: 'objects',
                    itemKind: '',
                    candidate: '',
                    page: '',
                  })
                }
              >
                객체 속성 {objectCount}
              </button>
              <button
                type="button"
                aria-pressed={group === 'mappings' && !filters.itemKind}
                onClick={() =>
                  update({
                    group: 'mappings',
                    itemKind: '',
                    candidate: '',
                    page: '',
                  })
                }
              >
                매핑 {mappingCount}
              </button>
            </div>
            <ProposalSearch
              key={search}
              search={search}
              onSearch={(value) =>
                update({ q: value, page: '', candidate: '' })
              }
            />
          </div>
          <details className="rules-extra-filters">
            <summary>상세 필터</summary>
            <ProposalFilterForm
              key={params.toString()}
              initialFilters={filters}
              pending={query.isFetching}
              onSearch={(next) =>
                update({
                  ...Object.fromEntries(
                    fields.map((f) => [f.key, next[f.key] ?? '']),
                  ),
                  page: '',
                  candidate: '',
                })
              }
            />
          </details>
          {query.isPending ? (
            <LoadingState message="제안 목록을 불러오는 중…" />
          ) : query.isError ? (
            <ErrorState
              error={query.error}
              onRetry={() => void query.refetch()}
            />
          ) : (
            <>
              <p className="rules-result-count">후보 {filtered.length}건</p>
              <p
                className="rules-filter-summary"
                aria-label="적용된 후보 검색 조건"
              >
                적용 조건:{' '}
                {[
                  reviewLabels[filters.reviewStatus ?? ''] ?? '전체 검토 상태',
                  filters.itemKind
                    ? filterKindLabels[filters.itemKind]
                    : group === 'objects'
                      ? '객체 속성'
                      : '매핑',
                  filters.origin ? originLabels[filters.origin] : null,
                  filters.applyStatus ? applyLabels[filters.applyStatus] : null,
                  search ? `검색어: ${search}` : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
              {!selected ? (
                <EmptyState message="선택한 상태·유형·검색어에 해당하는 후보가 없습니다." />
              ) : (
                <div className="candidate-workbench">
                  <aside className="candidate-list" aria-label="검토 목록">
                    <div className="candidate-cards">
                      {visible.map((item) => (
                        <button
                          type="button"
                          key={item.id}
                          className="candidate-card"
                          aria-pressed={item.id === selected.id}
                          onClick={() => update({ candidate: String(item.id) })}
                        >
                          <strong>{proposalTitle(item)}</strong>
                          <span className="candidate-metadata">
                            {isObjectProposal(item) ? '객체 속성' : '매핑'} ·{' '}
                            {originLabels[item.origin] ?? item.origin}
                          </span>
                          <span className="candidate-metadata">
                            검토 상태:{' '}
                            {reviewLabels[item.reviewStatus] ??
                              item.reviewStatus}{' '}
                            · 적용 여부:{' '}
                            {applyLabels[item.applyStatus] ?? item.applyStatus}
                          </span>
                          <ProposalFlags item={item} />
                        </button>
                      ))}
                    </div>
                    <Pagination
                      page={page}
                      size={PROPOSAL_PAGE_SIZE}
                      totalElements={filtered.length}
                      compact
                      onChange={(next) =>
                        update({ page: String(next), candidate: '' })
                      }
                    />
                  </aside>
                  <ProposalDetail key={selected.id} embeddedId={selected.id} />
                </div>
              )}
            </>
          )}
        </>
      )}
    </div>
  )
}
