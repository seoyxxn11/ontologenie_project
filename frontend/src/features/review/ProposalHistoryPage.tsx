import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import DataTable from '../../components/DataTable'
import Pagination from '../../components/Pagination'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchProposal } from './api'
import { DECISION_PAGE_SIZE, fetchProposalDecisions } from './decisionApi'
import BusinessApprovalStatus from './BusinessApprovalStatus'
import type { BusinessApprovalSource } from './businessApproval'
import { applyLabels, proposalTitle, reviewLabels } from './labels'
import type { Proposal } from './types'

const decisionLabels: Record<string, string> = {
  APPROVE: '승인',
  REJECT: '거절',
  HOLD: '보류',
}

function dateTime(value: string | null) {
  return value ? formatKstDateTime(value) : '제공되지 않음'
}

function ApprovalStatus({ proposal }: { proposal: Proposal }) {
  return (
    <section className="space-y-4" aria-label="승인·반영 상태">
      <h3 className="font-semibold">개정 {proposal.revision} · 승인 상태</h3>
      <dl className="grid gap-4 sm:grid-cols-3">
        <div>
          <dt className="text-sm text-slate-500">검토 상태</dt>
          <dd>
            {reviewLabels[proposal.reviewStatus] ?? proposal.reviewStatus}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-slate-500">반영 상태</dt>
          <dd>{applyLabels[proposal.applyStatus] ?? proposal.applyStatus}</dd>
        </div>
        <div>
          <dt className="text-sm text-slate-500">반영 시각</dt>
          <dd>{dateTime(proposal.appliedAt)}</dd>
        </div>
      </dl>
      {proposal.applyError && (
        <p role="alert" className="rounded-lg bg-red-50 p-3 text-red-800">
          반영 실패 사유: {proposal.applyError}
        </p>
      )}
      <p className="text-sm text-slate-600">
        현재 제안 개정의 상태입니다. 승인·보류·거절의 실행자, 처리 시각과 사유는
        활동 기록에서 확인하세요. 결정 이력이 있다는 것만으로 실제 반영이 완료된
        것은 아닙니다.
      </p>
    </section>
  )
}

function DecisionHistory({
  proposalId,
  page,
  onPageChange,
}: {
  proposalId: number
  page: number
  onPageChange: (page: number) => void
}) {
  const query = useQuery({
    queryKey: ['proposals', 'decisions', proposalId, page],
    queryFn: ({ signal }) => fetchProposalDecisions(proposalId, page, signal),
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  return (
    <section className="space-y-4" aria-label="결정 이력">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-semibold">검토 결정 이력</h3>
        <Button
          variant="secondary"
          pending={query.isFetching}
          onClick={() => {
            void query.refetch()
          }}
        >
          이력 새로고침
        </Button>
      </div>
      <p className="text-sm text-slate-600">
        이 제안의 과거 개정을 포함한 전체 결정 이력입니다. 최신 결정부터 20개씩
        표시하며, 시각이 같으면 결정 번호가 큰 순서입니다. 개별 문제 건의
        보류·해소 기록과는 구분됩니다.
      </p>
      {query.isPending || query.isFetching || query.isPaused ? (
        <LoadingState
          message={
            query.isPaused
              ? '연결이 복구되면 결정 이력을 불러옵니다.'
              : '결정 이력을 불러오는 중…'
          }
        />
      ) : query.isError ? (
        <ErrorState
          error={query.error}
          onRetry={() => {
            void query.refetch()
          }}
        />
      ) : (
        <>
          {!query.data.data.length ? (
            <EmptyState message="이 페이지에 결정 이력이 없습니다." />
          ) : (
            <DataTable label="검토 결정 이력">
              <thead className="bg-slate-50 text-slate-600">
                <tr>
                  {['처리 시각·실행자', '개정·대안', '결정', '사유'].map(
                    (label) => (
                      <th key={label} className="p-3">
                        {label}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {query.data.data.map((record) => (
                  <tr
                    key={record.id}
                    className="border-t border-slate-200 align-top"
                  >
                    <td className="p-3">
                      <p>{dateTime(record.decidedAt)}</p>
                      <p className="text-sm text-slate-500">
                        사용자 #{record.decidedBy} · 결정 #{record.id}
                      </p>
                    </td>
                    <td className="p-3">
                      <p>개정 {record.itemRevision}</p>
                      <p className="text-sm text-slate-500">
                        {record.selectedOptionId === null
                          ? '대안 지정 없음'
                          : `대안 ID #${record.selectedOptionId}`}
                      </p>
                    </td>
                    <td className="p-3">
                      {decisionLabels[record.decision] ?? record.decision}
                    </td>
                    <td className="max-w-lg whitespace-pre-wrap break-words p-3">
                      {record.reason}
                    </td>
                  </tr>
                ))}
              </tbody>
            </DataTable>
          )}
          <Pagination
            page={page}
            size={DECISION_PAGE_SIZE}
            totalElements={query.data.meta.totalElements}
            onChange={onPageChange}
          />
        </>
      )}
    </section>
  )
}

export default function ProposalHistoryPage({
  businessApprovalSource,
}: {
  businessApprovalSource?: BusinessApprovalSource
}) {
  const { id } = useParams()
  const [params, setParams] = useSearchParams()
  const proposalId = Number(id)
  const validId =
    /^\d+$/.test(id ?? '') && Number.isSafeInteger(proposalId) && proposalId > 0
  const view = params.get('view')
  const activity = view === 'activity'
  const business = view === 'business'
  const rawPage = params.get('historyPage') ?? '0'
  const page =
    /^\d+$/.test(rawPage) && Number.isSafeInteger(Number(rawPage))
      ? Number(rawPage)
      : 0
  const returnParams = new URLSearchParams(params)
  returnParams.delete('view')
  returnParams.delete('historyPage')
  const returnSearch = returnParams.size ? `?${returnParams}` : ''
  const query = useQuery({
    queryKey: ['proposals', 'detail', proposalId],
    queryFn: ({ signal }) => fetchProposal(proposalId, signal),
    enabled: validId,
    retry: false,
  })

  function changeView(
    nextView: 'approval' | 'activity' | 'business',
    nextPage = 0,
  ) {
    const next = new URLSearchParams(params)
    next.delete('view')
    next.delete('historyPage')
    if (nextView !== 'approval') next.set('view', nextView)
    if (nextPage > 0) next.set('historyPage', String(nextPage))
    setParams(next)
  }

  return (
    <div className="management-page space-y-5">
      <Link
        className="text-sm text-violet-700 underline"
        to={
          validId ? `/admin/rules/${proposalId}${returnSearch}` : '/admin/rules'
        }
      >
        {validId ? '제안 상세로 돌아가기' : '제안 목록으로 돌아가기'}
      </Link>
      <PageHeader
        title="규칙 이력"
        description="제안의 승인 상태, 활동 기록과 보류 건을 확인합니다."
      />
      {!validId ? (
        <EmptyState message="올바른 제안 주소가 아닙니다." />
      ) : query.isPending ? (
        <LoadingState message="제안 상태를 불러오는 중…" />
      ) : query.isError ? (
        <ErrorState
          error={query.error}
          onRetry={() => {
            void query.refetch()
          }}
        />
      ) : (
        <section className="management-panel space-y-5">
          <h2 className="text-xl font-semibold">{proposalTitle(query.data)}</h2>
          <p className="text-sm text-slate-500">
            제안 #{proposalId} · 현재 개정 {query.data.revision}
          </p>
          <div
            role="group"
            aria-label="규칙 이력 보기"
            className="flex flex-wrap gap-2"
          >
            <Button
              variant={activity || business ? 'secondary' : 'primary'}
              aria-pressed={!activity && !business}
              onClick={() => changeView('approval')}
            >
              승인 상태
            </Button>
            <Button
              variant={activity ? 'primary' : 'secondary'}
              aria-pressed={activity}
              onClick={() => changeView('activity')}
            >
              활동 기록
            </Button>
            <Button
              variant={business ? 'primary' : 'secondary'}
              aria-pressed={business}
              onClick={() => changeView('business')}
            >
              현업 승인 상태
            </Button>
          </div>
          {activity ? (
            <DecisionHistory
              proposalId={proposalId}
              page={page}
              onPageChange={(next) => changeView('activity', next)}
            />
          ) : business ? (
            <BusinessApprovalStatus
              key={`${proposalId}:${query.data.revision}`}
              proposalId={proposalId}
              revision={query.data.revision}
              source={businessApprovalSource}
            />
          ) : (
            <ApprovalStatus proposal={query.data} />
          )}
        </section>
      )}
    </div>
  )
}
