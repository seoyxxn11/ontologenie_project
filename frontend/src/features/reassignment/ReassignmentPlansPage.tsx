import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import PageHeader from '../../components/PageHeader'
import Pagination from '../../components/Pagination'
import StatusBadge from '../../components/StatusBadge'
import { positiveId } from '../explorer/searchState'
import { formatCount } from '../explorer/format'
import {
  fetchReassignmentPlan,
  fetchReassignmentPlans,
  PLAN_PAGE_SIZE,
} from './api'
import SavedEquipmentSelect from './SavedEquipmentSelect'
import {
  formatPlanDateTime,
  operationLabel,
  planStatusLabel,
  workOrderLabel,
} from './format'
import PlanObjectLink from './PlanObjectLink'
import { PlanContent } from './ReassignmentPlanDetails'
import type { ReassignmentPlanFilters } from './types'
import './reassignment.css'

function PlanFilters({
  filters,
  onSearch,
  pending,
}: {
  filters: ReassignmentPlanFilters
  onSearch: (filters: ReassignmentPlanFilters) => void
  pending: boolean
}) {
  const [equipmentId, setEquipmentId] = useState(filters.fromEquipmentId)
  const [creatorName, setCreatorName] = useState(filters.createdByName ?? '')
  return (
    <>
      <form
        className="plan-filters"
        aria-label="재배정 계획 조회 조건"
        onSubmit={(event) => {
          event.preventDefault()
          onSearch({
            fromEquipmentId: equipmentId,
            createdByName: creatorName.trim() || undefined,
          })
        }}
      >
        <SavedEquipmentSelect value={equipmentId} onChange={setEquipmentId} />
        <label>
          생성자
          <input
            type="search"
            value={creatorName}
            maxLength={100}
            placeholder="이름 입력"
            onChange={(event) => setCreatorName(event.target.value)}
          />
        </label>
        <div className="plan-filter-actions">
          <Button type="submit" pending={pending}>
            조회
          </Button>
          <Button
            variant="secondary"
            disabled={pending}
            onClick={() => {
              setEquipmentId(undefined)
              setCreatorName('')
              onSearch({})
            }}
          >
            초기화
          </Button>
        </div>
      </form>
    </>
  )
}

function PlanPanel({ planId }: { planId: number }) {
  const query = useQuery({
    queryKey: ['reassignment-plans', planId],
    queryFn: ({ signal }) => fetchReassignmentPlan(planId, signal),
    retry: false,
  })
  if (query.isPending)
    return <LoadingState message="계획 상세를 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    )
  const plan = query.data
  const targets = [...new Set(plan.items.map((item) => item.toEquipmentId))]
  return (
    <>
      <div className="plan-detail-title">
        <h3>계획 {plan.id}</h3>
        <StatusBadge tone="info">{planStatusLabel(plan.status)}</StatusBadge>
      </div>
      <div className="plan-movement" aria-label="설비 변경 경로">
        <div>
          <span>기존 설비</span>
          <PlanObjectLink objectId={plan.fromEquipmentId} />
        </div>
        <span aria-label="대상 설비로">→</span>
        <div>
          <span>대상 설비</span>
          {targets.length
            ? targets.map((id) => <PlanObjectLink key={id} objectId={id} />)
            : '—'}
        </div>
      </div>
      <PlanContent plan={plan} resolveEquipmentNames />
      {plan.agentConversationId !== null ? (
        <Link
          className="ui-button ui-button--primary plan-conversation-link"
          to={`/agent?conversation=${plan.agentConversationId}`}
        >
          Agent 대화로 이동 ↗
        </Link>
      ) : (
        <p className="plan-help">이 계획에 연결된 Agent 대화가 없습니다.</p>
      )}
    </>
  )
}

export default function ReassignmentPlansPage() {
  const [params, setParams] = useSearchParams()
  const selectedId = positiveId(params.get('plan'))
  const filters: ReassignmentPlanFilters = {
    fromEquipmentId: positiveId(params.get('fromEquipmentId')) ?? undefined,
    createdBy: positiveId(params.get('createdBy')) ?? undefined,
    createdByName: params.get('createdByName')?.trim() || undefined,
  }
  const page = Math.min(positiveId(params.get('page')) ?? 0, 2147483647)
  const selectedButton = useRef<HTMLButtonElement | null>(null)
  const list = useQuery({
    queryKey: ['reassignment-plans', 'list', filters, page],
    queryFn: ({ signal }) => fetchReassignmentPlans(filters, page, signal),
    retry: false,
  })

  function search(next: ReassignmentPlanFilters) {
    const changed =
      next.fromEquipmentId !== filters.fromEquipmentId ||
      next.createdBy !== filters.createdBy ||
      next.createdByName !== filters.createdByName
    const nextParams = new URLSearchParams(params)
    for (const key of [
      'fromEquipmentId',
      'createdBy',
      'createdByName',
    ] as const) {
      if (next[key]) nextParams.set(key, String(next[key]))
      else nextParams.delete(key)
    }
    nextParams.delete('page')
    if (changed) nextParams.delete('plan')
    setParams(nextParams)
    if (!changed && page === 0) void list.refetch()
  }

  function select(id: number | null) {
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      if (id === null) next.delete('plan')
      else next.set('plan', String(id))
      return next
    })
  }

  const total = list.data?.meta?.totalElements
  return (
    <div className="reassignment-page">
      <PageHeader
        title="재배정 계획"
        description="저장한 재배정 계획과 연결된 Agent 대화를 확인하세요."
      />
      <div className="plan-workspace">
        <section className="plan-panel" aria-labelledby="plan-list-title">
          <header className="plan-panel-heading">
            <h2 id="plan-list-title">
              재배정 계획{' '}
              {total !== undefined && (
                <span className="plan-count">{formatCount(total)}건</span>
              )}
            </h2>
            <span className="plan-help">최신순</span>
          </header>
          <PlanFilters
            key={JSON.stringify(filters)}
            filters={filters}
            onSearch={search}
            pending={list.isFetching}
          />
          <div className="plan-list-body" aria-busy={list.isFetching}>
            {list.isPending && (
              <LoadingState message="계획 목록을 불러오는 중…" />
            )}
            {list.isError && (
              <ErrorState
                error={list.error}
                onRetry={() => void list.refetch()}
              />
            )}
            {list.isSuccess &&
              (list.data.data.length === 0 ? (
                <EmptyState message="조회할 수 있는 재배정 계획이 없습니다. 조회 조건을 바꾸거나 Agent에서 계획을 저장해 주세요." />
              ) : (
                <DataTable label="재배정 계획 목록">
                  <thead>
                    <tr>
                      <th>계획</th>
                      <th>작업지시</th>
                      <th>설비 변경</th>
                      <th>공정</th>
                      <th>상태</th>
                      <th>생성자</th>
                      <th>생성 시각</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.data.map((plan) => (
                      <tr
                        key={plan.id}
                        data-selected={selectedId === plan.id}
                        onClick={(event) => {
                          if (
                            !(event.target as HTMLElement).closest('a, button')
                          )
                            select(plan.id)
                        }}
                      >
                        <td>
                          <button
                            type="button"
                            className="plan-select"
                            aria-label={`계획 ${plan.id}`}
                            aria-pressed={selectedId === plan.id}
                            onClick={(event) => {
                              selectedButton.current = event.currentTarget
                              select(plan.id)
                            }}
                          >
                            {plan.id}
                          </button>
                        </td>
                        <td>
                          {[...new Set(plan.items.map(workOrderLabel))].join(
                            ', ',
                          ) || '—'}
                        </td>
                        <td>
                          <div className="plan-route">
                            <PlanObjectLink objectId={plan.fromEquipmentId} />
                            <span>→</span>
                            <div>
                              {[
                                ...new Set(
                                  plan.items.map((item) => item.toEquipmentId),
                                ),
                              ].map((id) => (
                                <PlanObjectLink key={id} objectId={id} />
                              ))}
                            </div>
                          </div>
                        </td>
                        <td>
                          {[...new Set(plan.items.map(operationLabel))].join(
                            ' · ',
                          ) || '—'}
                        </td>
                        <td>
                          <StatusBadge tone="info">
                            {planStatusLabel(plan.status)}
                          </StatusBadge>
                        </td>
                        <td>
                          {plan.createdByName?.trim() || '이름 확인 불가'}
                        </td>
                        <td>{formatPlanDateTime(plan.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </DataTable>
              ))}
          </div>
          <footer className="plan-pagination">
            {list.data?.meta && (
              <Pagination
                page={page}
                size={list.data.meta.size || PLAN_PAGE_SIZE}
                totalElements={list.data.meta.totalElements}
                pending={list.isFetching}
                compact
                showSummary={false}
                showSinglePage
                onChange={(nextPage) =>
                  setParams((previous) => {
                    const next = new URLSearchParams(previous)
                    if (nextPage) next.set('page', String(nextPage))
                    else next.delete('page')
                    return next
                  })
                }
              />
            )}
          </footer>
        </section>
        <section
          className="plan-panel plan-detail"
          aria-labelledby="plan-detail-title"
        >
          <header className="plan-panel-heading">
            <h2 id="plan-detail-title">계획 상세</h2>
            {selectedId !== null && (
              <Button
                variant="text"
                onClick={() => {
                  select(null)
                  selectedButton.current?.focus()
                }}
              >
                닫기
              </Button>
            )}
          </header>
          <div className="plan-detail-body">
            {selectedId === null ? (
              <EmptyState message="왼쪽 목록에서 계획을 선택해 주세요." />
            ) : (
              <PlanPanel key={selectedId} planId={selectedId} />
            )}
          </div>
        </section>
      </div>
    </div>
  )
}
