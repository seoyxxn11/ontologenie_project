import HistoryBack from '../../components/HistoryBack'
import './proposalHistory.css'
import './rules.css'
import { formatKstDateTime } from '../../utils/dateTime'
import { useState } from 'react'
import StatusBadge from '../../components/StatusBadge'
import DecisionSnapshot, { DecisionOptionLink } from './DecisionSnapshot'
import type { ProposalDecisionRecord } from './decisionApi'
import { useQuery } from '@tanstack/react-query'
import { useLocation, useParams, useSearchParams } from 'react-router'
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
  proposal,
  page,
  onPageChange,
}: {
  proposal: Proposal
  page: number
  onPageChange: (page: number) => void
}) {
  const proposalId = proposal.id
  const [snapshot, setSnapshot] = useState<{
    record: ProposalDecisionRecord
    kind: 'revision' | 'option'
  } | null>(null)
  const query = useQuery({
    queryKey: ['proposals', 'decisions', proposalId, page],
    queryFn: ({ signal }) => fetchProposalDecisions(proposalId, page, signal),
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  return (
    <section className="decision-history" aria-label="결정 이력">
      <div className="history-identity">
        <div>
          <h2>{proposalTitle(proposal)}</h2>
          <p>
            제안 #{proposalId} · 현재 개정 {proposal.revision}
          </p>
        </div>
        <Button
          variant="text"
          className="history-refresh"
          aria-label="이력 새로고침"
          data-tooltip="이력 새로고침"
          pending={query.isFetching}
          onClick={() => {
            void query.refetch()
          }}
        >
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5" />
            <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5" />
          </svg>
        </Button>
      </div>
      <div className="decision-results">
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
        ) : !query.data.data.length ? (
          <EmptyState message="이 페이지에 결정 이력이 없습니다." />
        ) : (
          <DataTable label="검토 결정 이력" className="decision-table">
            <colgroup>
              <col style={{ width: '22%' }} />
              <col style={{ width: '13%' }} />
              <col style={{ width: '15%' }} />
              <col style={{ width: '10%' }} />
              <col style={{ width: '40%' }} />
            </colgroup>
            <thead>
              <tr>
                {['처리 시각', '실행자', '검토 대상', '결정', '사유'].map(
                  (x) => (
                    <th key={x} scope="col">
                      {x}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody>
              {query.data.data.map((record) => {
                const [date, ...time] = dateTime(record.decidedAt).split(' ')
                return (
                  <tr key={record.id}>
                    <td>
                      <p>{date}</p>
                      <p className="history-time">{time.join(' ')}</p>
                    </td>
                    <td>
                      {record.decidedByName?.trim() ||
                        '사용자 #' + record.decidedBy}
                    </td>
                    <td>
                      <button
                        className="history-detail-link"
                        type="button"
                        aria-haspopup="dialog"
                        onClick={() =>
                          setSnapshot({ record, kind: 'revision' })
                        }
                      >
                        개정 {record.itemRevision}
                      </button>
                      {record.selectedOptionId === null ? (
                        <p className="history-muted">대안 지정 없음</p>
                      ) : (
                        <DecisionOptionLink
                          proposalId={proposalId}
                          record={record}
                          onOpen={() => setSnapshot({ record, kind: 'option' })}
                        />
                      )}
                    </td>
                    <td>
                      <StatusBadge
                        tone={
                          record.decision === 'APPROVE'
                            ? 'success'
                            : record.decision === 'HOLD'
                              ? 'warning'
                              : record.decision === 'REJECT'
                                ? 'danger'
                                : 'neutral'
                        }
                      >
                        {decisionLabels[record.decision] ?? record.decision}
                      </StatusBadge>
                    </td>
                    <td>{record.reason}</td>
                  </tr>
                )
              })}
            </tbody>
          </DataTable>
        )}
      </div>
      {query.data &&
        !query.isError &&
        !query.isPending &&
        !query.isFetching &&
        !query.isPaused && (
          <Pagination
            page={page}
            size={DECISION_PAGE_SIZE}
            totalElements={query.data.meta.totalElements}
            pending={query.isFetching}
            onChange={onPageChange}
          />
        )}
      {snapshot && (
        <DecisionSnapshot
          key={snapshot.record.id + '-' + snapshot.kind}
          proposal={proposal}
          record={snapshot.record}
          kind={snapshot.kind}
          onClose={() => setSnapshot(null)}
        />
      )}
    </section>
  )
}

export default function ProposalHistoryPage({
  businessApprovalSource,
}: {
  businessApprovalSource?: BusinessApprovalSource
}) {
  const location = useLocation()
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
    setParams(next, { state: location.state })
  }

  return (
    <div
      className={`management-page proposal-history-page ${activity ? 'proposal-history-page--activity' : ''}`}
    >
      <PageHeader
        title="규칙 이력"
        description="제안의 승인 상태, 활동 기록과 보류 건을 확인합니다."
      />
      <div className="history-navigation">
        <HistoryBack
          className="history-back"
          dividerClassName="history-divider"
          label="제안 상세로 돌아가기"
        />
        <div
          role="group"
          aria-label="규칙 이력 보기"
          className="management-tabs"
        >
          {(
            [
              { key: 'activity', label: '활동 기록' },
              { key: 'approval', label: '승인 상태' },
              { key: 'business', label: '현업 승인 상태' },
            ] as const
          ).map((tab) => (
            <button
              type="button"
              key={tab.key}
              aria-pressed={
                tab.key === 'activity'
                  ? activity
                  : tab.key === 'business'
                    ? business
                    : !activity && !business
              }
              onClick={() => changeView(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>
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
        <section className="history-content">
          {!activity && (
            <div className="history-identity">
              <div>
                <h2>{proposalTitle(query.data)}</h2>
                <p>
                  제안 #{proposalId} · 현재 개정 {query.data.revision}
                </p>
              </div>
            </div>
          )}

          {activity ? (
            <DecisionHistory
              key={proposalId}
              proposal={query.data}
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
